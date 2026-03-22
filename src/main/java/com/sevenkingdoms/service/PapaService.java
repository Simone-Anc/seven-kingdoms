package com.sevenkingdoms.service;

import com.sevenkingdoms.exception.GameException;
import com.sevenkingdoms.model.*;
import com.sevenkingdoms.model.enums.ActionType;
import com.sevenkingdoms.model.enums.CharacterType;
import com.sevenkingdoms.model.enums.GamePhase;
import com.sevenkingdoms.repository.GameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PapaService {

	private final GameRepository gameRepository;
	private final GameService gameService;
	private final NotificationService notificationService;

	// ── Province raggiungibili dal Papa ───────────────────────────────────────

	public List<Integer> getReachableProvinces(String gameId) {
	    GameState state = gameRepository.getOrThrow(gameId);
	    GameCharacter papa = state.getCharacter(CharacterType.PAPA);
	    Province current = state.getProvince(papa.getCurrentProvinceId());

	    List<Integer> reachable = new ArrayList<>();
	    reachable.addAll(current.getAdjacentIds());
	    reachable.addAll(current.getSeaAdjacentIds());

	    return reachable.stream()
	            .collect(Collectors.toList());
	}

	// ── Province dove il Papa può piazzare il segnalino religioso ─────────────
	// Normale: non in guerra
	// Potenziata: anche in guerra

	public List<Integer> getReligiousTargets(String gameId, int movedToProvinceId, ActionType actionType) {
		GameState state = gameRepository.getOrThrow(gameId);
		Province papaProv = state.getProvince(movedToProvinceId);

		List<Integer> targets = papaProv.getAdjacentIds().stream().filter(id -> {
			Province p = state.getProvince(id);
			if (p.isEconomicFullOfReligious())
				return false;
			if (actionType == ActionType.NORMALE && p.isAtWar())
				return false;
			return true;
		}).collect(Collectors.toList());

		// Aggiunge anche la provincia del Papa stesso
		if (!papaProv.isEconomicFullOfReligious()) {
			if (actionType == ActionType.POTENZIATA || !papaProv.isAtWar()) {
				targets.add(movedToProvinceId);
			}
		}

		return targets;
	}

	// ── Esegue l'azione del Papa ──────────────────────────────────────────────

	public GameState executeAction(String gameId, String playerId, ActionType actionType, int moveToProvinceId,
			int religiousTargetProvinceId, Integer cathedralProvinceId) {

		GameState state = gameRepository.getOrThrow(gameId);
		gameService.validatePhase(state, GamePhase.PLAYER_ACTIONS);
		gameService.validateCurrentPlayer(state, playerId);
		gameService.validatePlayerNotActed(state, playerId);

		Player player = state.getCurrentPlayer();
		GameCharacter papa = state.getCharacter(CharacterType.PAPA);

		// ── 1. Movimento ──────────────────────────────────────────────────────
		if (moveToProvinceId < 0) {
			throw GameException.invalidAction("Il Papa deve spostarsi in una provincia adiacente");
		}
		Province current = state.getProvince(papa.getCurrentProvinceId());
		if (!current.isAdjacentByLand(moveToProvinceId)
		        && !current.isAdjacentBySea(moveToProvinceId)) {
		    throw GameException.invalidAction("Il Papa non può raggiungere questa provincia");
		}
		if (moveToProvinceId == papa.getCurrentProvinceId()) {
			throw GameException.invalidAction("Il Papa non può restare nella stessa provincia");
		}
		papa.moveTo(moveToProvinceId);

		Province papaProvince = state.getProvince(papa.getCurrentProvinceId());

		// ── 2. Validazioni ────────────────────────────────────────────────────
		if (actionType == ActionType.POTENZIATA) {
			if (!player.hasPapalFavor()) {
				throw GameException.invalidAction("Azione potenziata richiede un Favore Papale");
			}
			player.spendPapalFavor();
			executePotentiata(state, player, papa, papaProvince, religiousTargetProvinceId, cathedralProvinceId);
		} else {
			executeNormale(state, papaProvince, religiousTargetProvinceId);
		}

		// ── 3. Segna azione eseguita ──────────────────────────────────────────
		papa.markUsed();
		player.setActionDone(true);
		state.addEvent(player.getName() + " usa il Papa in " + papaProvince.getName());

		advanceTurn(state);
		GameState saved = gameRepository.save(state);
		notificationService.broadcastGameState(saved);
		return saved;
	}

	// ── Azione normale ────────────────────────────────────────────────────────
	// Piazza segnalino influenza religiosa partendo dal numero più alto libero
	// Se l'indicatore eco è su quel slot → lo sposta verso il basso

	private void executeNormale(GameState state, Province papaProvince, int religiousTargetProvinceId) {

		Province target = validateReligiousTarget(state, papaProvince, religiousTargetProvinceId, false);

		placeReligiousMarker(state, target);
	}

	// ── Azione potenziata ─────────────────────────────────────────────────────
	// Come normale MA:
	// - Può agire anche in province in guerra
	// - In più: piazza un Vescovo (cubetto del giocatore) in una cattedrale

	private void executePotentiata(GameState state, Player player, GameCharacter papa, Province papaProvince,
			int religiousTargetProvinceId, Integer cathedralProvinceId) {

		Province target = validateReligiousTarget(state, papaProvince, religiousTargetProvinceId, true);

		placeReligiousMarker(state, target);

		// Piazza vescovo nella cattedrale scelta (se fornita)
		if (cathedralProvinceId != null && cathedralProvinceId >= 0) {
			Province cathedralProv = state.getProvince(cathedralProvinceId);
			if (cathedralProv.getCathedralPlayerIndex() >= 0) {
				throw GameException.invalidAction("La cattedrale di " + cathedralProv.getName() + " è già occupata");
			}
			if (!player.takeCubeFromReserve()) {
				throw GameException.invalidAction("Nessun cubetto disponibile nella riserva");
			}
			cathedralProv.setCathedralPlayerIndex(player.getColorIndex());
			player.setBishops(player.getBishops() + 1);
			state.addEvent(player.getName() + " piazza un Vescovo in " + cathedralProv.getName());
		}
	}

	// ── Helper: valida target segnalino religioso ─────────────────────────────

	private Province validateReligiousTarget(GameState state, Province papaProvince, int targetId, boolean allowWar) {
		// Target deve essere la provincia del Papa o una adiacente
		boolean isPapaProv = targetId == papaProvince.getId();
		boolean isAdjacent = papaProvince.isAdjacentByLand(targetId);
		if (!isPapaProv && !isAdjacent) {
			throw GameException
					.invalidAction("Il segnalino deve essere nella provincia del Papa o in una adiacente via terra");
		}

		Province target = state.getProvince(targetId);

		if (!allowWar && target.isAtWar()) {
			throw GameException.invalidAction("L'azione normale del Papa non può agire in una provincia in Guerra");
		}

		if (target.isEconomicFullOfReligious()) {
			throw GameException.invalidAction(
					"La traccia economica di " + target.getName() + " è già piena di segnalini religiosi");
		}

		if (state.getReligiousMarkersRemaining() <= 0) {
			throw GameException.invalidAction("Non ci sono più segnalini influenza religiosa disponibili");
		}

		return target;
	}

	// ── Helper: piazza segnalino religioso ────────────────────────────────────
	// Parte dal numero più alto libero della traccia economica
	// Se l'indicatore eco (economicValue) è su quello slot → lo sposta di -1

	private void placeReligiousMarker(GameState state, Province target) {
		// Il segnalino occupa il numero più alto non ancora occupato da religiosi
		// religiousMarkers indica quanti ne sono già presenti
		// Slot occupati da religiosi: da 5 verso il basso
		// Es: 0 religiosi → prossimo slot è 5
		// 1 religioso → prossimo slot è 4
		// 2 religiosi → prossimo slot è 3 ecc.
		int slotToOccupy = 5 - target.getReligiousMarkers(); // slot numerico (1-5)

		// Se l'indicatore eco è su quel slot → lo sposta verso il basso
		if (target.getEconomicValue() == slotToOccupy) {
			if (slotToOccupy > 1) {
				target.setEconomicValue(slotToOccupy - 1);
				state.addEvent(
						"Indicatore economico di " + target.getName() + " scende a " + target.getEconomicValue());
			}
			// Se già a 1 non si sposta ulteriormente
		}

		target.setReligiousMarkers(target.getReligiousMarkers() + 1);
		state.setReligiousMarkersRemaining(state.getReligiousMarkersRemaining() - 1);
		state.addEvent("Segnalino religioso piazzato in " + target.getName() + " (slot " + slotToOccupy + ")");
	}

	// ── Helper: avanza turno ──────────────────────────────────────────────────

	private void advanceTurn(GameState state) {
		if (state.allPlayersActed()) {
			state.setPhase(GamePhase.PASSIVE_ACTIONS);
			state.addEvent("Tutti i giocatori hanno agito. Inizio fase passiva.");
		} else {
			state.advanceToNextPlayer();
		}
	}
}
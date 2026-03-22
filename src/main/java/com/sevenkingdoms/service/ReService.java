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
public class ReService {

	private final GameRepository gameRepository;
	private final GameService gameService;
	private final NotificationService notificationService;

	// ── Province raggiungibili dal Re (per evidenziarle nel frontend) ─────────

	public List<Integer> getReachableProvinces(String gameId) {
		GameState state = gameRepository.getOrThrow(gameId);
		GameCharacter re = state.getCharacter(CharacterType.RE);
		Province current = state.getProvince(re.getCurrentProvinceId());

		// Il Re può spostarsi via terra o via mare, non in province in guerra
		List<Integer> reachable = new ArrayList<>();
		reachable.addAll(current.getAdjacentIds());
		reachable.addAll(current.getSeaAdjacentIds());

		return reachable.stream().filter(id -> !state.getProvince(id).isAtWar()).collect(Collectors.toList());
	}

	// ── Province dove il Re può piazzare cubetti dopo il movimento ────────────

	public List<Integer> getPlaceableProvinces(String gameId, int movedToProvinceId) {
		GameState state = gameRepository.getOrThrow(gameId);
		Province kingProv = state.getProvince(movedToProvinceId);

		// Provincia del Re + adiacenti via terra, non in guerra, non piene
		List<Integer> placeable = kingProv.getAdjacentIds().stream().filter(id -> {
			Province p = state.getProvince(id);
			return !p.isAtWar() && !p.isFull();
		}).collect(Collectors.toList());

		if (!kingProv.isAtWar() && !kingProv.isFull()) {
			placeable.add(movedToProvinceId);
		}
		return placeable;
	}

	// ── Esegue l'azione del Re ────────────────────────────────────────────────

	public GameState executeAction(String gameId, String playerId, ActionType actionType, int moveToProvinceId,
			List<CubePlacementRequest> placements) {

		GameState state = gameRepository.getOrThrow(gameId);
		gameService.validatePhase(state, GamePhase.PLAYER_ACTIONS);
		gameService.validateCurrentPlayer(state, playerId);
		gameService.validatePlayerNotActed(state, playerId);

		Player player = state.getCurrentPlayer();
		GameCharacter re = state.getCharacter(CharacterType.RE);

		// ── 1. Movimento ──────────────────────────────────────────────────────
		if (moveToProvinceId < 0) {
			throw GameException.invalidAction("Il Re deve spostarsi in una provincia adiacente");
		}
		Province current = state.getProvince(re.getCurrentProvinceId());
		if (!current.isAdjacentByLand(moveToProvinceId) && !current.isAdjacentBySea(moveToProvinceId)) {
			throw GameException.invalidAction("Il Re non può raggiungere questa provincia");
		}
		if (moveToProvinceId == re.getCurrentProvinceId()) {
			throw GameException.invalidAction("Il Re non può restare nella stessa provincia");
		}
		re.moveTo(moveToProvinceId);

		Province kingProvince = state.getProvince(re.getCurrentProvinceId());

		// ── 2. Validazione: il Re non può agire in guerra ─────────────────────
		if (kingProvince.isAtWar()) {
			throw GameException.invalidAction("Il Re non può agire in una provincia in Guerra");
		}

		// ── 3. Esegui azione ──────────────────────────────────────────────────
		if (actionType == ActionType.POTENZIATA) {
			if (!player.hasPapalFavor()) {
				throw GameException.invalidAction("Azione potenziata richiede un Favore Papale");
			}
			player.spendPapalFavor();
			executePotentiata(state, player, re, kingProvince, placements);
		} else {
			executeNormale(state, player, re, kingProvince, placements);
		}

		// ── 4. Segna azione eseguita ──────────────────────────────────────────
		re.markUsed();
		player.setActionDone(true);
		state.addEvent(player.getName() + " usa il Re in " + kingProvince.getName());

		advanceTurn(state);
		GameState saved = gameRepository.save(state);
		notificationService.broadcastGameState(saved);
		return saved;
	}

	// ── Azione normale ────────────────────────────────────────────────────────
	// 2 cubetti nella provincia del Re + 1 in una adiacente
	// Almeno 1 DEVE essere di un altro giocatore (se si piazzano ≥2)

	private void executeNormale(GameState state, Player player, GameCharacter re, Province kingProvince,
			List<CubePlacementRequest> placements) {

		if (placements == null || placements.isEmpty())
			return; // mossa legale senza cubetti

		int cubesInKingProv = (int) placements.stream().filter(p -> p.provinceId() == re.getCurrentProvinceId())
				.count();
		int cubesInAdjProv = (int) placements.stream().filter(p -> p.provinceId() != re.getCurrentProvinceId()).count();

		// Regola: max 2 nella provincia del Re, max 1 in una adiacente
		if (cubesInKingProv > 2) {
			throw GameException.invalidAction("Puoi piazzare al massimo 2 cubetti nella provincia del Re");
		}
		if (cubesInAdjProv > 1) {
			throw GameException.invalidAction("Puoi piazzare al massimo 1 cubetto in una provincia adiacente");
		}

		// Regola: almeno 1 di colore diverso se si piazzano ≥2 cubetti
		if (placements.size() >= 2) {
			boolean hasOtherColor = placements.stream().anyMatch(p -> p.playerIndex() != player.getColorIndex());
			if (!hasOtherColor) {
				throw GameException.invalidAction("Almeno un cubetto deve essere di un altro colore");
			}
		}

		for (CubePlacementRequest req : placements) {
			Province target = state.getProvince(req.provinceId());
			validateNormalTarget(re, kingProvince, target);
			placeCube(state, req.playerIndex(), target);
		}
	}

	// ── Azione potenziata ─────────────────────────────────────────────────────
	// 2 cubetti in 2a e 3a posizione, scalando a destra
	// Può agire anche in province pacificate o con eserciti, NON in guerra

	private void executePotentiata(GameState state, Player player, GameCharacter re, Province kingProvince,
			List<CubePlacementRequest> placements) {

		if (placements == null || placements.size() != 2) {
			throw GameException.invalidAction("L'azione potenziata richiede esattamente 2 cubetti");
		}

		for (CubePlacementRequest req : placements) {
			Province target = state.getProvince(req.provinceId());

			if (target.isAtWar()) {
				throw GameException.invalidAction("Non puoi piazzare in una provincia in Guerra");
			}
			if (target.getId() != re.getCurrentProvinceId() && !kingProvince.isAdjacentByLand(req.provinceId())
					&& !kingProvince.isAdjacentBySea(req.provinceId())) {
				throw GameException.invalidAction("Provincia non raggiungibile");
			}

			Player owner = state.getPlayers().get(req.playerIndex());
			if (!owner.takeCubeFromReserve()) {
				throw GameException.invalidAction("Nessun cubetto disponibile per " + owner.getName());
			}

			// Inserisce sempre in posizione 1 (il secondo cubetto
			// va in posizione 1 e spinge il precedente in posizione 2)
			List<Cube> ejected = target.insertAtPosition(1, Cube.ofPlayer(req.playerIndex()));

			//I cubetti espulsi tornano in riserva
			for (Cube c : ejected) {
				if (c.isColored()) {
					state.getPlayers().get(c.getPlayerIndex()).returnCubeToReserve();
				}
				// I cubetti invasione espulsi tornano nel pool (non tracciamo)
			}
		}
	}

	// ── Helper: valida target azione normale ──────────────────────────────────

	private void validateNormalTarget(GameCharacter re, Province kingProvince, Province target) {
		boolean isKingProvince = target.getId() == re.getCurrentProvinceId();
		boolean isAdjacent = kingProvince.isAdjacentByLand(target.getId());

		if (!isKingProvince && !isAdjacent) {
			throw GameException
					.invalidAction("Il cubetto deve essere nella provincia del Re o in una adiacente via terra");
		}
		if (target.isAtWar()) {
			throw GameException.invalidAction("Non puoi piazzare in una provincia in Guerra");
		}
		if (target.isFull()) {
			throw GameException.invalidAction("Provincia piena: " + target.getName());
		}
	}

	// ── Helper: piazza un cubetto ─────────────────────────────────────────────

	private void placeCube(GameState state, int playerIndex, Province target) {
		Player owner = state.getPlayers().get(playerIndex);
		if (!owner.takeCubeFromReserve()) {
			throw GameException.invalidAction("Nessun cubetto disponibile per " + owner.getName());
		}
		target.addCube(Cube.ofPlayer(playerIndex));
	}

	// ── Helper: avanza il turno ───────────────────────────────────────────────

	private void advanceTurn(GameState state) {
		if (state.allPlayersActed()) {
			state.setPhase(GamePhase.PASSIVE_ACTIONS);
			state.addEvent("Tutti i giocatori hanno agito. Inizio fase passiva.");
		} else {
			state.advanceToNextPlayer();
		}
	}

	public record CubePlacementRequest(int provinceId, int playerIndex) {
	}
}
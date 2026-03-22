package com.sevenkingdoms.model;

import com.sevenkingdoms.model.enums.ProvinceStatus;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@Builder
public class Province {

    private int id;                      // 0-6
    private String name;                 // Es: "Nordheim", "Capitale"
    private boolean isCapital;           // La Capitale ha 8 slot politici
    private List<Integer> adjacentIds;   // Province adiacenti via terra
    private List<Integer> seaAdjacentIds;// Province adiacenti via mare (solo movimento)

    // Traccia politica: lista ordinata di cubetti (pos 0 = Governatore)
    // maxSize = 5 (o 8 per Capitale)
    private List<Cube> politicalTrack;
    private int maxPoliticalSlots;       // 5 o 8

    // Traccia economica: valore corrente 1-5
    private int economicValue;           // Indicatore giallo (valore attuale)
    private int religiousMarkers;        // Quanti segnalini influenza religiosa ci sono

    // Cattedrale: playerIndex del vescovo (-1 se vuota)
    private int cathedralPlayerIndex;

    // Stato della provincia
    private ProvinceStatus status;

    // ──────────────────────────────────────────────
    // Factory method
    // ──────────────────────────────────────────────

    public static Province create(int id, String name, boolean isCapital,
                                  List<Integer> adjacent, List<Integer> seaAdjacent,
                                  int startEco, int slots) {

        List<Cube> track = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            track.add(Cube.empty());
        }

        return Province.builder()
                .id(id)
                .name(name)
                .isCapital(isCapital)
                .adjacentIds(new ArrayList<>(adjacent))
                .seaAdjacentIds(new ArrayList<>(seaAdjacent))
                .politicalTrack(track)
                .maxPoliticalSlots(slots)
                .economicValue(startEco)
                .religiousMarkers(0)
                .cathedralPlayerIndex(-1)
                .status(ProvinceStatus.NORMALE)
                .build();
    }

    // ──────────────────────────────────────────────
    // Traccia politica
    // ──────────────────────────────────────────────

    public boolean isFull() {
        return politicalTrack.stream().noneMatch(Cube::isEmpty);
    }

    public boolean hasRoom() {
        return politicalTrack.stream().anyMatch(Cube::isEmpty);
    }

    public int freeSlots() {
        return (int) politicalTrack.stream().filter(Cube::isEmpty).count();
    }

    /** Aggiunge un cubetto nella prima posizione libera (slot EMPTY) */
    public boolean addCube(Cube cube) {
        for (int i = 0; i < politicalTrack.size(); i++) {
            if (politicalTrack.get(i).isEmpty()) {
                politicalTrack.set(i, cube);
                updateStatus();
                return true;
            }
        }
        return false; // piena
    }

    /** Inserisce in posizione 1 (azione potenziata Re) scalando i cubetti a destra.
     *  Gli slot EMPTY vengono compattati a destra prima di inserire.
     *  I cubetti che escono dalla traccia tornano in riserva. */
    public List<Cube> insertAtPosition(int position, Cube cube) {
        List<Cube> ejected = new ArrayList<>();

        // Trova il primo slot EMPTY da destra e rimuovilo (compatta verso destra)
        // per fare spazio all'inserimento senza aumentare la lunghezza della lista
        for (int i = politicalTrack.size() - 1; i >= 0; i--) {
            if (politicalTrack.get(i).isEmpty()) {
                politicalTrack.remove(i);
                break;
            }
        }

        // Ora inserisci in posizione (la lista ha maxPoliticalSlots - 1 elementi)
        politicalTrack.add(position, cube);

        // Se la lista è ancora troppo grande (traccia piena senza EMPTY), espelli l'ultimo
        while (politicalTrack.size() > maxPoliticalSlots) {
            Cube expelled = politicalTrack.remove(politicalTrack.size() - 1);
            if (!expelled.isEmpty()) ejected.add(expelled);
        }

        updateStatus();
        return ejected;
    }

    /** Rimuove il cubetto più a destra */
    public Optional<Cube> removeRightmostCube() {
        if (politicalTrack.isEmpty()) return Optional.empty();
        Cube removed = politicalTrack.remove(politicalTrack.size() - 1);
        updateStatus();
        return Optional.of(removed);
    }

    /** Aggiunge cubetto invasione nella posizione più a destra libera (EMPTY).
     *  Se non ci sono slot EMPTY, rimpiazza il cubetto colorato più a destra → GUERRA */
    public boolean addInvasionCube() {
        if (status == ProvinceStatus.PACIFICATA) return false;

        // 1. Cerca il primo slot EMPTY da destra → metti invasione lì
        for (int i = politicalTrack.size() - 1; i >= 0; i--) {
            if (politicalTrack.get(i).isEmpty()) {
                politicalTrack.set(i, Cube.invasion());
                updateStatus();
                return true;
            }
        }

        // 2. Nessun slot vuoto → rimpiazza il cubetto colorato più a destra → GUERRA
        for (int i = politicalTrack.size() - 1; i >= 0; i--) {
            if (politicalTrack.get(i).isColored()) {
                politicalTrack.set(i, Cube.invasion());
                status = ProvinceStatus.GUERRA;
                return true;
            }
        }

        return false; // solo invasioni, niente da fare
    }

    public Optional<Cube> getGovernor() {
        if (politicalTrack.isEmpty()) return Optional.empty();
        return Optional.of(politicalTrack.get(0));
    }

    public int countCubesOfPlayer(int playerIndex) {
        return (int) politicalTrack.stream()
                .filter(c -> c.getPlayerIndex() == playerIndex)
                .count();
    }

    public int countInvasionCubes() {
        return (int) politicalTrack.stream().filter(Cube::isInvasion).count();
    }

    public boolean isFullOfInvasion() {
        return isFull() && politicalTrack.stream().allMatch(Cube::isInvasion);
    }

    // ──────────────────────────────────────────────
    // Traccia economica
    // ──────────────────────────────────────────────

    public void increaseEconomic() {
        int maxEco = 5 - religiousMarkers;
        if (economicValue < maxEco) economicValue++;
    }

    public void decreaseEconomic() {
        if (economicValue > 1) {
            economicValue--;
        } else {
            // Se già a 1: rimuovi cubetto colorato più a destra e genera guerra
            for (int i = politicalTrack.size() - 1; i >= 0; i--) {
                if (politicalTrack.get(i).isColored()) {
                    politicalTrack.set(i, Cube.invasion());
                    status = ProvinceStatus.GUERRA;
                    break;
                }
            }
        }
    }

    public boolean addReligiousMarker() {
        if (religiousMarkers >= 5) return false;
        // Sposta indicatore economico verso il basso se occupato
        if (economicValue >= (5 - religiousMarkers)) {
            economicValue = Math.max(1, economicValue - 1);
        }
        religiousMarkers++;
        return true;
    }

    public boolean isEconomicFullOfReligious() {
        return religiousMarkers >= 5;
    }

    // ──────────────────────────────────────────────
    // Status
    // ──────────────────────────────────────────────

    public void refreshStatus() { updateStatus(); }

    private void updateStatus() {
        if (status == ProvinceStatus.GUERRA) return; // La guerra non si rimuove automaticamente
        boolean hasInvasion = politicalTrack.stream().anyMatch(Cube::isInvasion);
        boolean hasEmpty    = politicalTrack.stream().anyMatch(Cube::isEmpty);
        boolean hasColored  = politicalTrack.stream().anyMatch(Cube::isColored);
        // Pacificata SOLO se: piena, nessun invasione, nessuno slot vuoto, almeno un colorato
        if (isFull() && !hasInvasion && !hasEmpty && hasColored) {
            status = ProvinceStatus.PACIFICATA;
        } else {
            status = ProvinceStatus.NORMALE;
        }
    }

    public boolean isPacified() {
        return status == ProvinceStatus.PACIFICATA;
    }

    public boolean isAtWar() {
        return status == ProvinceStatus.GUERRA;
    }

    public void resolveWar() {
        status = ProvinceStatus.NORMALE;
        updateStatus();
    }

    // ──────────────────────────────────────────────
    // Adiacenza
    // ──────────────────────────────────────────────

    public boolean isAdjacentByLand(int provinceId) {
        return adjacentIds.contains(provinceId);
    }

    public boolean isAdjacentBySea(int provinceId) {
        return seaAdjacentIds.contains(provinceId);
    }

    public boolean isAdjacent(int provinceId) {
        return isAdjacentByLand(provinceId) || isAdjacentBySea(provinceId);
    }
}
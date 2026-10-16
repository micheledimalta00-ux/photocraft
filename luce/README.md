# Luce

Editor fotografico **open source** per Android, ispirato al flusso di lavoro di Lightroom mobile e al
progetto PhotoCraft. Modifiche non distruttive, interfaccia scura da camera oscura, layout adattivo
in verticale e in orizzontale. Nessun account, nessun cloud, nessuna pubblicità.

## Funzioni

| Area | Strumenti |
|---|---|
| Libreria | Tutte le foto, album, foto modificate (con miniatura aggiornata), fotocamera, selettore foto, apertura file, "Apri con / Condividi" da altre app |
| Predefiniti | 20 predefiniti in 6 gruppi + predefiniti personali (crea / elimina) |
| Profili | 15 profili (Colore, Vivido, Ritratto, Paesaggio, Cinema, Pellicola, B&N con filtri…) con Quantità 0–200% |
| Ritaglia | Proporzioni libere/bloccate, rotazione 90°, rifletti, raddrizza ±45° con riempimento automatico |
| Maschere | Pennello (aggiungi/gomma, dimensione, sfumatura, flusso), Lineare, Radiale; inverti, duplica, sovrapposizione rossa; 12 regolazioni locali |
| Luce | Esposizione, Contrasto, Luci, Ombre, Bianchi, Neri, Curva a punti RGB/R/G/B, Curva parametrica, Auto |
| Colore | Bilanciamento del bianco (Auto/Come scattato), Temperatura, Tinta, Vividezza, Saturazione, Miscelazione HSL a 8 canali / Miscela B&N, Color grading a 3 vie + globale, Fusione, Bilanciamento |
| Effetti | Texture, Chiarezza, Rimozione foschia, Vignettatura post-ritaglio (5 parametri), Granulosità |
| Dettaglio | Nitidezza (fattore, raggio, dettaglio, mascheratura), Riduzione disturbo luminanza e colore |
| Ottica | Distorsione, Vignettatura dell'obiettivo, Aberrazione cromatica |
| Flusso | Annulla/Ripeti, Prima/Dopo (tieni premuto sulla foto), istogramma con avvisi di ritaglio, zoom a due dita e doppio tocco, copia/incolla impostazioni |
| Esporta | JPG (qualità regolabile) o PNG, fino a 4096 px, salva in Immagini/Luce o condividi |

## Installazione

Ogni push sulla cartella `luce/` avvia la GitHub Action **luce**, che esegue i test del motore,
compila l'APK firmato e lo pubblica nella pagina **Releases** del repository come `Luce.apk`.
Dal telefono: apri l'ultima release, scarica `Luce.apk` e installalo.

Compilazione locale (Android Studio o riga di comando, JDK 17):

```
cd luce
gradle :app:assembleRelease
```

## Struttura

- `engine/` — motore di elaborazione in Kotlin puro (nessuna dipendenza Android), testato con JUnit:
  geometria, pipeline dei toni e del colore, maschere, curve, istogramma, auto, predefiniti, JSON.
- `data/` — caricamento immagini con EXIF, MediaStore, archivio modifiche, esportazione.
- `ui/` — Jetpack Compose: libreria, editor, pannelli, cursori, curva, ruote colore.

## Limiti noti

- Niente file RAW/DNG (Android non li decodifica nativamente): JPG, PNG, WebP, HEIC.
- Niente maschere automatiche con IA, rimozione/correttivo, né sincronizzazione cloud.
- L'anteprima lavora fino a 2400 px; l'esportazione fino a 4096 px per stare nella memoria del telefono.

"Lightroom" e "Adobe" sono marchi di Adobe Inc.; Luce è un progetto indipendente e non è affiliato ad Adobe.

Licenza: MIT.

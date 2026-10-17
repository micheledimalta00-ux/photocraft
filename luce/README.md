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
| Geometria | Upright (Auto, Livello, Verticale, Completo) da rilevamento delle linee; Verticale, Orizzontale, Ruota, Aspetto, Scala, Spostamento X/Y; Vincola ritaglio |
| Luce | Esposizione, Contrasto, Luci, Ombre, Bianchi, Neri, avviso di ritaglio sull'immagine, Auto; Curva a punti RGB/R/G/B e parametrica con divisioni delle regioni |
| Colore | Bilanciamento del bianco (Come scattato, Auto, contagocce), Temperatura, Tinta, Vividezza, Saturazione; **Colore puntuale** (fino a 8 campioni, spostamenti, varianza, intervalli, visualizzazione); Miscelazione HSL a 8 canali / Miscela B&N; Color grading a 3 vie + globale; **Calibrazione** (tinta ombre, primari R/G/B) |
| Effetti | Texture, Chiarezza, Rimozione foschia, Vignettatura (priorità luci / colore / sovrapposizione pittura), Granulosità |
| Dettaglio | Nitidezza (fattore, raggio, dettaglio, mascheratura), Riduzione disturbo (luminanza, dettaglio, contrasto; colore, dettaglio, uniformità) |
| Ottica | Rimuovi aberrazione cromatica, Distorsione, Vignettatura, Aberrazione cromatica, Rimuovi frange viola/verdi con intervalli di tonalità |
| **Maschere** | Selezioni intelligenti sul telefono: **Soggetto, Cielo, Sfondo, Oggetti** (tocca l'oggetto), **Persone** (persona intera, pelle del viso, pelle del corpo, sopracciglia, sclera, iride e pupilla, labbra, denti, capelli, abbigliamento), **Paesaggio** (cielo, acqua, vegetazione, montagne, architettura, terreno naturale e artificiale). Strumenti: Pennello (dimensione, sfumatura, flusso, densità, maschera automatica, gomma), Gradiente lineare, Gradiente radiale, Intervallo colore (fino a 5 campioni, rifinisci), Intervallo luminanza (intervallo, sfumature, uniformità). Componenti combinabili con **Aggiungi / Sottrai / Interseca**, inverti componente o maschera, Quantità 0–200%, rinomina, duplica, duplica e inverti, sovrapposizione (colore, su nero, su bianco, B&N, opacità). 19 regolazioni locali: Temperatura, Tinta, Esposizione, Contrasto, Luci, Ombre, Bianchi, Neri, Tonalità, Saturazione, Colore, Texture, Chiarezza, Rimozione foschia, Nitidezza, Disturbo, Moiré, Rimuovi frange |
| Flusso | Annulla/Ripeti, Prima/Dopo (tieni premuto sulla foto), istogramma con avvisi di ritaglio, zoom a due dita e doppio tocco, copia/incolla impostazioni |
| Esporta | JPG (qualità regolabile) o PNG, fino a 4096 px, salva in Immagini/Luce o condividi |

Le selezioni intelligenti usano modelli MediaPipe eseguiti interamente sul telefono, senza connessione:
DeepLab v3, segmentazione persone a 6 classi, punti del viso (478), DeepLab ADE20K (150 classi) e selezione interattiva.

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
- Niente intervallo di profondità (richiede dati di profondità della fotocamera), Upright guidato, rimozione/correttivo né sincronizzazione cloud.
- L'anteprima lavora fino a 2400 px; l'esportazione fino a 4096 px per stare nella memoria del telefono.

"Lightroom" e "Adobe" sono marchi di Adobe Inc.; Luce è un progetto indipendente e non è affiliato ad Adobe.

Licenza: MIT.

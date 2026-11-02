# Screen Annotator

Registratore schermo per Android con **annotazioni dal vivo** e **scia a scomparsa automatica**.
Disegni, scrivi e cerchi sopra qualsiasi app mentre registri: i tratti finiscono nel video e svaniscono da soli dopo il tempo scelto.

## Scaricare l'APK

Ogni push su `main` compila l'app con GitHub Actions. L'APK si scarica dalla pagina **Releases** del repo
(apri il link dal telefono e installa). La firma e' sempre la stessa, quindi le nuove versioni si installano sopra la precedente.

## Funzioni

- Registrazione schermo H.264 + AAC: audio del dispositivo, microfono o entrambi; risoluzione, fps e bitrate regolabili
- Salvataggio automatico in galleria (`Movies/ScreenAnnotator`)
- Tile nelle impostazioni rapide per avviare/fermare
- Barra flottante trascinabile, riducibile a bolla: penna, matita, evidenziatore, testo, forme (cerchio, freccia, linea, rettangolo), gomma, colore, spessore, opacita', annulla, ripristina, cancella tutto
- Interruttore **disegno / interazione**: con il disegno spento tocchi e scorri le app normalmente
- Modalita' **temporanea** (svanisce) e **permanente**, cambiabile durante la registrazione
- Scia: ritardo prima della dissolvenza, durata, dissolvenza dell'intero disegno oppure punto per punto
- Tutte le preferenze salvate automaticamente

## Prima configurazione

1. Installa l'APK e apri l'app.
2. Concedi **Sovrapposizione alle altre app** quando richiesto.
3. Tocca **Avvia registrazione** e, nella finestra di sistema, scegli **Intero schermo** (con "Una singola app" i disegni non vengono registrati).

### Mostrare i tocchi di sistema (facoltativo)

Android non permette a un'app normale di attivare i tocchi di sistema. Concedi il permesso una sola volta dal computer:

```
adb shell pm grant com.imslept.screenannotator android.permission.WRITE_SECURE_SETTINGS
```

Poi attiva "Mostra i tocchi sullo schermo". Senza il permesso, in modalita' disegno compare comunque un cerchio sotto il dito.

## Limiti noti

- La barra flottante **non puo' essere esclusa con certezza dal video**: Android registra tutto cio' che e' sullo schermo. Riducila a bolla o spostala in un angolo.
- Per le regole di Android 12+ gli overlay che lasciano passare i tocchi possono avere al massimo l'80% di opacita': i disegni non arrivano al 100%.
- L'orientamento e' quello dello schermo all'avvio; non ruotare il telefono durante la registrazione.
- La gomma cancella l'intero tratto toccato e non e' annullabile.
- App con contenuti protetti (banca, DRM) bloccano cattura video/audio.
- Non testata su dispositivo reale al momento della creazione: la compilazione e' verificata da GitHub Actions, il comportamento su Pixel va provato.

## Sviluppo

Kotlin, viste Material 3, nessuna dipendenza oltre AndroidX e Material. `minSdk 29`, `targetSdk 35`.
Compilazione locale: `gradle assembleRelease` (Gradle 8.10+, JDK 17).

## Se «Mostra sopra altre app» è grigio (impostazioni con restrizioni)

Android blocca alcuni permessi alle app installate da un file APK scaricato.

**Via 1 (nell'app, senza PC)**
1. Apri l'app e premi «Attiva» nella scheda «Prepara l'app».
2. Nella pagina del permesso tocca l'interruttore grigio e poi OK. Serve a far comparire il menu.
3. Torna indietro, apri Impostazioni → App → Screen Annotator, tocca i tre puntini in alto a destra → «Consenti impostazioni con restrizioni».
4. Torna nell'app e premi «Attiva».

**Via 2 (senza menu): installare con un installer a sessioni**
Le app installate con un installer a sessioni (per esempio Obtainium, che segue le Release di questo repository) non vengono limitate. Disinstalla l'app, poi installa l'APK da Obtainium.

**Via 3 (da computer con adb)**
```
adb shell appops set com.imslept.screenannotator ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.imslept.screenannotator SYSTEM_ALERT_WINDOW allow
```

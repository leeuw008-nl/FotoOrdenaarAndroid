# FotoVerkenner voor Windows

Eerste zelfstandige Windows-desktopuitvoering. De Android-app blijft ongewijzigd.

## Functies
- Fotomap kiezen en foto's/video's in onderliggende mappen inventariseren.
- Bestandslijst met type, datum, bestandsgrootte en GPS.
- Voorbeeldweergave van foto's.
- EXIF-opnamedatum, camera, afmetingen en GPS uitlezen.
- Sorteren op bestandsdatum, opnamedatum, naam of plaats.
- Zoeken op bestandsnaam, map en opgehaalde plaatsnaam.
- Filteren op jaar, maand en dag.
- Foto's openen en video's afspelen met de standaard Windows-app.
- GPS-posities openen in OpenStreetMap en optioneel een plaatsnaam ophalen.

## EXE bouwen
1. Open GitHub > Actions.
2. Kies Build FotoVerkenner Windows.
3. Start Run workflow op branch windows/fotoverkenner-desktop-v1.
4. Download artifact FotoVerkenner-Windows-x64 na een geslaagde build.
5. Pak het ZIP-bestand uit en start FotoVerkenner.exe.

## Teststatus en grenzen
De EXE wordt op een Windows-runner gebouwd. Test daarna op een Windows-pc met een kopie van een fotomap. De app wijzigt, verplaatst of verwijdert geen foto's. Video's openen in de standaard videospeler; ingebouwde videobediening en videot thumbnails volgen later. Plaatsnamen worden op verzoek via OpenStreetMap/Nominatim opgehaald en vereisen internet. Grote collecties kunnen tijd kosten bij de eerste inventarisatie.

Build-trigger: 2026-11-06.

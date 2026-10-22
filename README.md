# FotoOrdenaar (Android)

Android-projectbron voor een eenmalige foto-organizer.

## Functionaliteit in deze versie
- Laat de gebruiker via de Android-mapkiezer een bronmap kiezen.
- Verwerkt afbeeldingsbestanden die direct in de gekozen map staan (niet recursief).
- Gebruikt EXIF `DateTimeOriginal`, met `DateTime` als tweede keuze, voor de dagmap `jjjjmmdd`.
- Gebruikt EXIF GPS en Android Geocoder voor `Gemeente_Plaats_Locatie`.
- Maakt de structuur `jjjjmmdd/Locatie/` binnen de gekozen map.
- Kopieert de bestandsinhoud naar de nieuwe locatie en verwijdert het origineel pas na een geslaagde kopie.
- Bestanden zonder bruikbare EXIF-datum blijven op hun oorspronkelijke plaats en worden gemeld.
- De voorbeeldweergave analyseert de bestanden zonder ze te verplaatsen.

## Belangrijke beperkingen
- Dit is broncode, geen vooraf gebouwde of geteste APK.
- De locatiebepaling gebruikt Android Geocoder. De uitkomst hangt af van de gegevens die Android beschikbaar heeft; de specifieke naam van een gebouw of monument is niet gegarandeerd. Als reverse-geocoding niet lukt, gebruikt de app een GPS-label.
- Android's documentprovider bepaalt welke mapacties zijn toegestaan. Kies de bronmap via de systeemkiezer en geef toegang.
- De app verwerkt alleen bestanden direct in de geselecteerde map, niet bestaande submappen.
- Maak een reservekopie en test eerst met een kleine testmap. Bestanden zonder EXIF-datum worden niet verplaatst.
- Hoewel de app de bron pas verwijdert na kopiëren, is dit nog geen gevalideerde, productieklare migratietool.

## Bouwen in Android Studio
1. Installeer Android Studio met JDK 17-ondersteuning.
2. Pak het ZIP-bestand uit.
3. Open de map `FotoOrdenaarAndroid` (de map met `settings.gradle.kts`) als project.
4. Laat Gradle synchroniseren en eventuele Android SDK-componenten installeren.
5. Kies **Build > Build APK(s)**.
6. Android Studio toont een melding met de locatie van de APK.

## APK bouwen via GitHub Actions
Het project bevat `.github/workflows/android.yml`. Na het uploaden van het project naar een GitHub-repository kan de workflow handmatig worden gestart via **Actions > Build Android APK > Run workflow**. Bij een geslaagde build staat `app-debug.apk` als workflow-artifact klaar om te downloaden. Deze buildmethode is opgenomen maar in deze omgeving nog niet uitgevoerd.

# Kopanie-boxpvp (Fabric 1.20.1)

## Komendy
| Komenda | Opis |
|---|---|
| `/post1` | zaznacza pierwszy róg terenu tam gdzie stoisz |
| `/post2` | zaznacza drugi róg terenu |
| `/kop <blok>` | slot 1 (np. `/kop hay_block`) |
| `/kop2 <blok>` ... `/kop10 <blok>` | kolejne sloty - do 10 różnych bloków naraz |
| `/kop-start` | włącza automatyczne kopanie w terenie |
| `/kop-stop` | wyłącza kopanie |
| `/kop-lista` | pokazuje pozycje i sloty |
| `/kop-czysc` | czyści sloty |

## Budowanie
Wymagane: JDK 17.
1. W folderze projektu: `gradle wrapper --gradle-version 8.5` (jednorazowo) albo skopiuj `gradlew`/`gradle/` z https://github.com/FabricMC/fabric-example-mod
2. `./gradlew build` (Windows: `gradlew.bat build`)
3. Gotowy mod: `build/libs/Kopanie-boxpvp-1.0.0.jar` -> do folderu `mods` (razem z Fabric API).

## Ustawienia
Na górze `KopanieBoxPvp.java`: `BREAKS_PER_TICK` (szybkość kopania), `SCANS_PER_TICK`, `MAX_VOLUME`.

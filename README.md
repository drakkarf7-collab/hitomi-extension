# Extension Hitomi.la pour Tachimanga / Mihon / Tachiyomi (18+)

Extension source **Hitomi** (`eu.kanade.tachiyomi.extension.all.hitomi`), contenu NSFW.
Le dépôt se construit tout seul avec GitHub Actions : à chaque push sur `main`, l'APK est compilé
et publié dans la branche `repo` avec `index.min.json`, et c'est ce lien qu'on colle dans l'app.

## Lien à coller dans Tachimanga

```
https://raw.githubusercontent.com/drakkarf7-collab/hitomi-extension/repo/index.min.json
```

Tachimanga : **Réglages → Dépôts d'extensions (Extension repos) → Ajouter**, colle le lien, puis
**Extensions → Hitomi → Installer**. Active l'affichage du contenu NSFW si l'app le demande.

## Mettre le projet sur GitHub (une seule fois, environ 3 minutes)

1. Va sur https://github.com/new
   - Nom du dépôt : `hitomi-extension`
   - Visibilité : **Public** (obligatoire, sinon le lien raw ne marche pas)
   - Ne coche **rien** (pas de README, pas de .gitignore)
   - Clique sur **Create repository**
2. Dans un terminal ouvert dans ce dossier :
   ```
   git remote add origin https://github.com/<TON_PSEUDO_GITHUB>/hitomi-extension.git
   git push -u origin main repo
   ```
   Une fenêtre de connexion GitHub s'ouvre la première fois : connecte-toi.
3. C'est tout. La branche `repo` (déjà construite) est en ligne, donc le lien fonctionne tout de suite.
   À chaque modification poussée sur `main`, l'onglet **Actions** recompile et republie tout seul.
   Si les Actions sont désactivées : onglet **Actions → I understand… enable**.

## Ce que fait l'extension

| Fonction | Détail |
|---|---|
| Populaire | `popular/{today,week,month,year}` (période réglable dans les paramètres de la source) |
| Récent | `index-{langue}.nozomi`, 25 galeries par page via des requêtes HTTP Range |
| Recherche | Même syntaxe que le site : `female:yuri -male:x language:french type:manga artist:xxx`, `or`, mots libres (index B-tree `galleriesindex`), `orderby:popular`, `orderbykey:week`… |
| Filtres | Tri, type, tags, artistes, groupes, séries, personnages (séparés par des virgules, `-` pour exclure) |
| ID / lien | Tape `123456`, `id:123456` ou colle un lien hitomi.la pour ouvrir directement une galerie |
| Images | URLs recalculées à partir de `gg.js` (hash → sous-domaine `w1`/`w2` + chemin), avec un cache de 5 minutes |
| Format | WebP par défaut, AVIF en option |
| Langues | all, en, ja, zh, ko, fr, es, de, it, pt-BR, ru, pl, id, vi, th, tr, uk, ar, hu, cs, nl, tl |

Une galerie correspond à un manga avec un seul chapitre. L'URL réelle de chaque image est calculée
au moment de la lecture, parce que `gg.js` change régulièrement côté site.

## Structure

```
hitomi-extension/
├── .github/workflows/build.yml      # CI : build APK + publication de la branche `repo`
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew, gradlew.bat, gradle/wrapper/
├── signingkey.jks                   # clé de signature privée, locale uniquement (voir plus bas)
├── scripts/
│   ├── make_repo.py                 # génère index.min.json, index.json, repo.json, apk/, icon/
│   └── make_icon.py                 # (re)génère les icônes
└── hitomi/
    ├── build.gradle.kts             # nom, version, lib 1.4
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/mipmap-*/ic_launcher.png
        └── kotlin/eu/kanade/tachiyomi/extension/all/hitomi/
            ├── HitomiFactory.kt     # une source par langue
            ├── Hitomi.kt            # source principale (browse, détails, chapitre, pages, réglages)
            ├── HitomiSearch.kt      # nozomi + recherche B-tree (port de search.js)
            ├── HitomiImages.kt      # gg.js → URLs d'images (port de common.js)
            └── HitomiFilters.kt
```

## Version de la lib

L'extension est compilée avec l'API **1.4** (`keiyoushi/extensions-lib v1.4.2.1`), donc la version
affichée est `1.4.1`. C'est l'API la plus largement supportée (Tachimanga, Mihon et ses forks).
L'API 1.6 de Keiyoushi n'est pas encore acceptée par toutes les apps, et Tachimanga en fait partie.

## Mettre à jour l'extension

Modifie le code, augmente `extVersionCode` dans `hitomi/build.gradle.kts`, puis :
```
git commit -am "Update" && git push
```
L'app proposera la mise à jour.

## Clé de signature

L'APK est signé avec `signingkey.jks` (alias `hitomi`, mot de passe `hitomi-ext`). Ce fichier est
**privé** : il n'est pas publié sur GitHub (il est dans `.gitignore`). Garde-le précieusement, car
toutes les mises à jour doivent être signées avec la même clé, sinon elles ne s'installent pas.

La branche `repo` publiée contient déjà l'APK signé, donc le lien marche sans rien configurer.
Pour que GitHub Actions recompile tout seul les prochaines versions, ajoute dans
**Settings → Secrets and variables → Actions → New repository secret** :

| Nom | Valeur |
|---|---|
| `SIGNING_KEY` | contenu de `signingkey.jks` en base64 (PowerShell : `[Convert]::ToBase64String([IO.File]::ReadAllBytes("signingkey.jks"))`) |
| `KEY_STORE_PASSWORD` | `hitomi-ext` |
| `KEY_ALIAS` | `hitomi` |
| `KEY_PASSWORD` | `hitomi-ext` |

Sans ces secrets, le workflow ne fait rien et la branche `repo` actuelle reste en ligne.

## Compiler en local (facultatif)

JDK 17 et Android SDK requis :
```
./gradlew assembleRelease
python scripts/make_repo.py
```
Le dossier `repo/` contient alors exactement ce qui est publié sur la branche `repo`.

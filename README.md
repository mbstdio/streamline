# Streamline TV

Application Android TV native (Java + Media3), destinée à une box Xiaomi sous **Android TV 11 ou ultérieur**. Aucun serveur à héberger.

## Fonctions

- Connexion Twitch par code et QR code à autoriser depuis un téléphone/ordinateur.
- Chaînes suivies actuellement en direct, exploration des lives et recherche de chaînes, avec pagination.
- Lecture HLS, sélection de qualité parmi les pistes prises en charge par la box, mode automatique et audio sans vidéo.
- Qualité préférée enregistrée dans les réglages : meilleure disponible (par défaut), automatique, 1080p, 720p, 480p, 360p ou 160p. La résolution exacte est sélectionnée à l’ouverture du direct, à la fréquence d’images la plus élevée disponible ; si elle est absente, le lecteur reste en automatique.
- Les directs sont classés par spectateurs selon l’ordre officiel Twitch. L’entrée dans une catégorie place le focus sur son premier direct.
- Chat en lecture seule, affichable/masquable, reconnexion automatique et prise en compte des suppressions de messages.
- Navigation à la télécommande, lecture/pause avec la touche multimédia, retour au catalogue et arrêt de la lecture en arrière-plan.

### Catalogue et lecteur TV (0.2.0)

- L’accueil regroupe **Suivis en direct**, **Chaînes live** et **Parcourir** en rangées horizontales. Les directs affichent preview 16:9, chaîne, titre, catégorie et nombre de spectateurs à droite. **Parcourir** présente les jaquettes des jeux/catégories populaires ; sélectionner un jeu affiche ses directs. Les résultats de recherche en direct récupèrent également leur preview et leur audience.
- **Gauche/droite** parcourt les cartes ; **haut/bas** passe d’une section à l’autre. Le titre trop long défile uniquement sur la carte sélectionnée. Au retour du lecteur, la sélection et les positions de défilement sont restaurées dans la session en cours. Chaque section dispose de son icône **Actualiser** à droite du titre, sans recharger les autres sections.
- La barre supérieure regroupe les icônes de recherche et réglages, puis l’avatar Twitch ouvrant le menu du compte et la déconnexion. Sur les résultats de recherche, **Retour** revient au catalogue.
- Icônes par [Remix Icon](https://remixicon.com), sous [Remix Icon License v1.0](https://github.com/Remix-Design/RemixIcon/blob/master/License), adaptées en drawables vectoriels Android (recherche, réglages, actualisation, compte de secours).
- Le lecteur reprend le placement de l’extension : informations en haut, contrôles superposés en bas (lecture/pause, rechargement et son à gauche ; qualité et chat à droite), chat masquable à droite.
- Les contrôles sont masqués au départ. **OK / bouton central** les affiche sans interrompre la lecture ; les flèches permettent alors de choisir une action et OK de l’activer. Ils restent visibles jusqu’à **Retour**.
- **Retour** ferme d’abord un menu ouvert ; sinon il masque les contrôles. Les appuis Retour pendant la **seconde** suivant leur disparition sont ignorés ; un nouvel appui après ce délai revient au catalogue. Les touches multimédias restent utilisables lorsque les contrôles sont masqués.

Le chat affiche les noms, couleurs et messages ; les émoticônes sont pour l’instant affichées sous forme de texte, sans images ni badges.

## Compte Twitch

Le Client ID de **notre application publique** est fixé dans l’APK et n’est pas modifiable dans les réglages : aucune saisie n’est nécessaire et les anciennes valeurs personnalisées sont ignorées. Choisir **Connexion Twitch**, puis scanner le QR code ou ouvrir l’adresse affichée et valider le code. Le QR code est généré localement, sans service tiers ; il utilise l’URL complète d’activation si Twitch la fournit, sinon l’adresse de vérification. Le Client ID n’est pas un secret. Le mot de passe Twitch et le Client Secret ne doivent **jamais** être saisis dans Streamline TV.

La création de cette application dans la console Twitch nécessite une intervention du propriétaire du compte (compte vérifié et double authentification). Le flux OAuth utilisé est le **Device Code Grant**, pour client de type **Public**. Si la console exige une URL de redirection à l’enregistrement, `http://localhost:3000` convient ; le flux par code n’utilise pas cette URL.

Permissions demandées : `user:read:follows` et `chat:read`. Aucune permission d’envoi de messages ou de modification des follows.

Les jetons d’accès et de renouvellement sont chiffrés avec AES-GCM et une clé Android Keystore non exportable. Les sauvegardes Android sont désactivées. Les jetons sont validés au démarrage de leur utilisation, puis au moins toutes les heures lors de l’utilisation authentifiée ; le renouvellement est automatique. La déconnexion efface la session locale ; l’autorisation peut également être révoquée depuis les connexions de ton compte Twitch.

## Lecture : adaptation de Streamline

Le portage reprend `../streamline/src/lib/twitch.ts` (`getChannelPlaylist`) :

1. Requête GraphQL `PlaybackAccessToken` avec la requête persistée de l’extension.
2. Construction de l’URL signée `usher.ttvnw.net/api/v2/channel/hls/<login>.m3u8`.
3. En-têtes `Origin` et `Referer` identiques à ceux de `../streamline/public/background.js`, appliqués aussi aux playlists et segments par Media3.
4. Lecture HLS native et sélection des pistes compatibles avec le matériel.

**Important :** les interfaces de lecture ne sont pas publiques et peuvent changer ou exiger de nouvelles vérifications. L’authentification officielle de l’application n’est pas la session web Firefox de l’extension. Pour cette première version, la lecture est anonyme, comme le mode sans OAuth de Streamline : les contenus nécessitant une authentification web/abonnement ne sont pas garantis. Il n’y a ni récupération des cookies Firefox, ni contournement des publicités, abonnements ou restrictions géographiques. Les API officielles Helix sont utilisées pour le compte, les listes et la recherche ; le chat utilise IRC sur WebSocket TLS avec la session de l’application.

## Compiler

Prérequis : JDK 17 ou 18, SDK Android (plateforme 35 et Build Tools 34.0.0), accès Internet aux dépôts Google/Maven. Le wrapper fournit Gradle 8.9. Les outils téléchargés localement sont dans `.tools/` (ignorés).

Sous PowerShell :

```powershell
.\scripts\build.ps1
```

Ou avec un SDK configuré via `ANDROID_HOME`/`local.properties` :

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Résultat du script : `artifacts/streamline-tv-0.2.0.apk`, accompagné de son empreinte SHA-256. Il s’agit d’un **APK de développement signé**, installable manuellement, pas d’une publication Play Store. Conserver la clé de développement Android locale pour que les prochaines versions puissent mettre celle-ci à jour sans désinstallation.

## Installer sur la box

Deux possibilités :

- Copier l’APK sur la box, l’ouvrir depuis un gestionnaire de fichiers et autoriser ce gestionnaire à installer des applications inconnues.
- Activer le mode développeur et le débogage réseau/USB si la box le propose, accepter la connexion sur la TV, puis utiliser ADB. La disponibilité du débogage réseau et son port dépendent du modèle/firmware Xiaomi.

```powershell
# Exemple seulement si le débogage réseau de la box est exposé sur le port 5555 :
.\.tools\android-sdk\platform-tools\adb.exe connect ADRESSE_IP_BOX:5555
.\.tools\android-sdk\platform-tools\adb.exe install -r .\artifacts\streamline-tv-0.2.0.apk
```

L’application apparaît dans le lanceur Android TV sous **Streamline TV**. Désactiver le débogage après installation si inutile.

## Vérifications

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
# Test réseau facultatif, sans compte, avec une chaîne actuellement en direct :
node .\scripts\check-twitch.mjs riotgames
```

Les tests couvrent l’encodage de la playlist, la validation des noms de chaînes, la connexion par code (attente, ralentissement, annulation, autorisation), le renouvellement/rangement des jetons, la vérification du client et des permissions, les réponses invalides et le parsing IRC (messages, PING, modération).

Des tests supplémentaires s’exécutent sur un appareil/émulateur Android TV : Client ID disponible au démarrage, décodage du QR code vers l’URL d’origine, navigation des réglages au pavé directionnel, affichage des contrôles et protection Retour de deux secondes, cartes horizontales et restauration du focus. Un test de previews réelles s’exécute si l’utilisateur a déjà autorisé son compte ; le test vidéo optionnel vérifie lecture, ouverture des contrôles sans pause, fermeture du menu qualité et arrêt/recréation du lecteur en arrière-plan. La vidéo utilise la lecture anonyme et appelle le lecteur depuis le test uniquement (aucun point d’entrée de test n’est exporté par l’application).

```powershell
# Avec un appareil ADB connecté ; remplacer riotgames si la chaîne est hors ligne :
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.liveChannel=riotgames'
```

Vérification de la version 0.2.0 : **17 tests unitaires et 7 tests sur un émulateur Android TV 11 réussis**, dont le chargement de vraies previews et la lecture HLS réelle sur une chaîne en direct, avec ouverture des contrôles sans pause et cycle arrière-plan/reprise. Le compte déjà autorisé sur l’émulateur est conservé ; le contenu du chat authentifié reste à vérifier visuellement par l’utilisateur.

À vérifier sur la vraie box : connexion de ton compte, navigation de tous les écrans à la télécommande, chaîne suivie/recherche, vidéo et son, qualités disponibles, chat et déconnexion/reconnexion réseau, mise en veille/retour à l’application. Les tests réseau et la compilation ne remplacent pas ces essais matériels.

### Boutons de l’émulateur sans effet

Si Retour/Accueil fonctionnent via `adb shell input keyevent` mais pas depuis la barre latérale, vérifier le fichier `%USERPROFILE%\.android\avd\streamline-tv-30.avd\config.ini` : **`hw.keyboard = yes`** est nécessaire avec ce profil TV. Arrêter l’émulateur avant de modifier ce réglage, puis le relancer normalement, sans effacer ses données. Ce problème a été reproduit puis corrigé avec Android Emulator 37.2.12 et l’image Android TV 11.

Raccourcis vérifiés, lorsque la fenêtre de l’émulateur a le focus et que les raccourcis sont envoyés aux contrôles de l’émulateur : **Ctrl + Retour arrière** pour Retour, **Ctrl + H** pour Accueil. Les boutons Retour et Accueil de la barre latérale fonctionnent également après cette correction.

Le test suivant ouvre les paramètres Android et vérifie les **vrais boutons de la fenêtre**, contrairement à une injection ADB. Il termine sur l’accueil Android TV :

```powershell
.\scripts\check-emulator-controls.ps1
```

******

### Historique des versions

******

# v0.2.2

###### 2026/09/15

* `Amélioration` compileSdk et targetSdk passent à 37 (Android 17) ; le comportement du plugin ne dépend pas de la nouvelle cible

# v0.2.1

###### 2026/09/13

* `Amélioration` Ressources traduites cohérentes, activation explicite du plugin et validation des paquets de publication

# v0.2.0

###### 2026/09/13

* `Amélioration` La vérification de compilation rejette les dépendances natives involontaires et produit un rapport JSON
* `Amélioration` Ressources traduites cohérentes, activation explicite du plugin et validation des paquets de publication

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `Note` Version actuelle. Publiée en préversion GitHub privée (5 ressources : APK signé, deux AAR de contrat gelés, manifeste de release et SHA256SUMS, toutes retéléchargées et vérifiées octet par octet) ; pas encore de publication publique
* `Note` Inerte par défaut après installation ; doit être activée manuellement dans les options développeur d'AutoJs6 -- voir la section « Installation et utilisation » du README
* `Fonctionnalité` Embarquer une bibliothèque de plateforme Android 36 vérifiée octet par octet comme bibliothèque de compilation R8, au lieu de dépendre du boot classpath de l'appareil ; corrige les échecs de compilation sur les appareils dont les JAR de boot ne sont que des coquilles de ressources
* `Fonctionnalité` Ajouter une collecte de diagnostics R8 bornée et expurgée des chemins, couvrant le démarrage du provider et les échecs d'import du moteur
* `Amélioration` Vérifier la sortie optimisée sur ART aux API 25/28/37 (y compris un émulateur à pages de 16 KiB) : réflexion, noms de classes composés à l'exécution, sérialisation, entrée côté script, contrôles du leurre supprimé, appels JNI arm64/x86/x86_64, et restauration de pile R8 Retrace après vérification du hachage du mapping

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `Correctif` Remplacer l'inspection du mode d'accès via `/proc/self/fdinfo` par des sondes noyau publiques `Os.read`/`Os.write` à zéro octet, résolvant les restrictions procfs de certains appareils (comme Sony API 28) tout en conservant le rejet des alias `Os.fstat`
* `Amélioration` Terminer l'acceptation Binder/PFD inter-APK sur 1 appareil physique et 2 émulateurs (API 25/28) : chemin nominal, cycle de vie, entrées hostiles et mort de processus -- 9/9 tests réussis

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `Correctif` Épingler le desugaring core-library/NIO (desugar_jdk_libs_nio 2.1.5) pour les API 24 à 28, corrigeant les échecs d'exécution dus aux capacités de bibliothèque cœur Java 11 manquantes sur les appareils plus anciens

# v0.1.0-provider-dev (local.2)

###### 2026/08/25

* `Correctif` Supprimer la dépendance du script de publication au hachage d'auto-chargement des modules PowerShell, garantissant un flux de signature reproductible indépendant de l'environnement

# v0.1.0-provider-dev (local.1)

###### 2026/08/25

* `Note` Première release locale signée (génération bootstrap) ; build reproductible avec deux instantanés hors ligne identiques à l'octet près, établissant le répertoire de release local append-only
* `Fonctionnalité` Plugin compilateur R8 explicite pour AutoJs6 : les scripts demandent une compilation release complète (shrinking + optimization + obfuscation) via `runtime.loadJarWithR8()`
* `Fonctionnalité` Une compilation renvoie cinq artefacts : DEX ZIP, mapping, seeds, usage et métadonnées retrace, chacun lié à un SHA-256 et revérifié indépendamment par l'hôte
* `Fonctionnalité` Sémantique sans repli : tout échec se termine en erreur R8 et ne bascule jamais silencieusement vers D8/dx ; l'hôte utilise le domaine de cache R8 dédié `autojs6:r8-compiler:v1`
* `Fonctionnalité` La compilation s'exécute dans le processus `:r8` propre au plugin, dans un bac à sable privé, n'accepte que les appelants AutoJs6 de même signature (protégés par la permission `org.autojs.permission.PLUGIN`) et ne demande aucune permission réseau ou stockage
* `Fonctionnalité` Validation stricte des entrées : bundle d'entrée canonique sans chemins, règles UTF-8 strictes avec directives dangereuses en fail-closed, et limites strictes sur les archives, les données de classes et les sorties
* `Fonctionnalité` Couverture complète de `minApi` 24-36 : un corpus de compatibilité R8 réel de 26 cellules Java/Kotlin avec réflexion, noms composés à l'exécution, JNI, sérialisation et contrôles du leurre supprimé
* `Fonctionnalité` Implémentation pure JVM ; un seul APK universel couvre toutes les architectures d'appareils
* `Dépendance` Embarquer Google R8 8.13.17 (Maven `com.android.tools:r8`)

# v0.1.0 (contract)

###### 2026/08/14

* `Note` Gel du contrat de protocole sans application ni comportement d'exécution ; cette entrée consigne l'établissement de la frontière d'interface
* `Fonctionnalité` Geler le protocole de compilation R8 indépendant 1.0 : espace de noms d'API `org.autojs.plugin.r8compiler.api`, action de découverte `org.autojs.plugin.R8_COMPILER`, identité de moteur `r8-compiler`
* `Fonctionnalité` Geler les formats canoniques de bundles de flux d'entrée/artefacts, les trois descripteurs AIDL et l'ABI JVM visible en Java ; publier les AAR de contrat 0.1.0 (protocol-wire-api et r8-compiler-api) en append-only

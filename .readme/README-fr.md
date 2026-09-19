<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p><img src="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="R8 Compiler icon" width="128" /></p>
  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases"><img alt="Release" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-R8-Compiler?label=Release" /></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/issues"><img alt="Issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-R8-Compiler?label=Issues" /></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/LICENSE"><img alt="License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-R8-Compiler?label=License" /></a>
  </p>

  <p>Plugin compilateur R8 autonome pour AutoJs6. Compile les JAR de script en DEX avec le profil release complet (shrink + optimize + obfuscate) dans un processus isolé</p>

  <p><sub>Stade actuel : préversion privée (le code source et les installateurs ne sont pas encore publics)</sub></p>
</div>

******

### Langues

******

Le fichier README.md est actuellement disponible dans les langues suivantes:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-en.md)
- Français [fr] # actuel
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ar.md)

******

### Introduction

******

Les scripts AutoJs6 peuvent charger un JAR avec `runtime.loadJar()` et appeler les classes Java qu'il contient. Cette voie par défaut utilise D8 pour une compilation JAR vers DEX brute, sans réduction ni obfuscation. Quand vous voulez qu'un artefact passe par un traitement release complet -- suppression du code mort (shrinking), optimisation du bytecode et obfuscation des identifiants -- il vous faut R8.

Ce plugin est une application installée séparément qui exécute une version épinglée du compilateur Google R8 dans son propre processus isolé, offrant à AutoJs6 un service de compilation release complet et explicite. Les scripts lancent une compilation via l'entrée dédiée `runtime.loadJarWithR8()` et doivent fournir des règles keep ; AutoJs6 récupère cinq artefacts (DEX, mapping, etc.), vérifie chacun d'eux, les met en cache et ne charge que le DEX vérifié.

La plus grande différence avec la voie intégrée : l'entrée R8 est explicite et sans repli. Une compilation échouée ne bascule jamais silencieusement vers D8/dx ; l'erreur est renvoyée telle quelle au script. Cela garantit un invariant simple : si le chargement réussit, l'artefact est passé par un traitement R8 complet.

Bonnes raisons d'installer ce plugin : vous devez réduire ou obfusquer un artefact JAR ; vous avez besoin des fichiers mapping pour désobfusquer des piles d'appels plus tard ; ou vous voulez une sémantique de compilation totalement déterministe (soit R8 complet, soit un échec net).

******

### Fonctionnement

******

Avec le plugin activé, un appel à `runtime.loadJarWithR8()` passe en gros par les étapes suivantes:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

Le plugin n'est responsable que des étapes 3 et 4 -- la compilation elle-même ; la capture et le gel de l'entrée, la validation des artefacts, la mise en cache et le chargement final des classes sont toujours effectués par AutoJs6. Les deux parties n'échangent que des descripteurs de fichiers via Binder, aucun chemin de fichier ne transite jamais sur le canal, et le plugin ne peut pas lire votre répertoire de scripts. Les résultats sont mis en cache par contenu d'entrée et paramètres de compilation dans un domaine de cache R8 dédié ; les chargements répétés de la même entrée touchent directement le cache, les données de cache corrompues sont évincées et recompilées automatiquement, et chaque artefact ouvert depuis le cache est d'abord re-haché.

******

### Fonctionnalités

******

- Compilation release complète : shrinking (suppression du code mort), optimisation et obfuscation sont toujours toutes activées, effectuées par R8 8.13.17 épinglé.
- Sémantique explicite sans repli silencieux : seul `runtime.loadJarWithR8()` utilise ce plugin ; tout échec se termine en erreur R8 et ne bascule jamais silencieusement vers D8/dx. `runtime.loadJar()` et `runtime.loadJarWithClasspath()` restent totalement inchangés.
- Cinq artefacts en un aller-retour : DEX ZIP, mapping (table d'obfuscation), seeds (éléments conservés), usage (éléments supprimés) et métadonnées retrace, chacun lié à un SHA-256 et revérifié indépendamment par l'hôte.
- La compilation s'exécute dans le processus `:r8` propre au plugin et dans un espace de travail privé, isolés d'AutoJs6 ; l'entrée est entièrement revalidée avant l'exécution de R8.
- Prend en charge des JAR de classpath ordonnés à la compilation et des règles consumer liées à leur JAR de classpath propriétaire ; les règles keep doivent être fournies explicitement, et aucune règle n'est jamais découverte implicitement dans les archives.
- Embarque une bibliothèque de plateforme Android 36 vérifiée octet par octet comme bibliothèque de compilation, au lieu de dépendre des JAR de boot-classpath de l'appareil potentiellement amputés ; `minApi` 24 à 36 est entièrement couvert par un corpus R8 réel.
- Prend en charge Android 7.0 (API 24) et supérieur ; vérifié sur appareils physiques et émulateurs API 25/28/37 (y compris un appareil à pages de 16 KiB) avec exécution ART réelle, appels JNI et restauration Retrace.
- Ne communique qu'avec un AutoJs6 de même signature (protégé par la permission `org.autojs.permission.PLUGIN`) et ne demande aucune permission réseau ou stockage.

******

### Relation avec le plugin DEX Compiler

******

L'écosystème AutoJs6 comporte deux plugins compilateurs indépendants. Ils sont complémentaires, ne se recouvrent pas et peuvent être installés côte à côte :

- [AutoJs6-Plugin-DEX-Compiler](https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler) sert la voie par défaut `runtime.loadJar()` / `runtime.loadJarWithClasspath()` : compilation brute avec un D8 plus récent, sans réduction ni obfuscation ; en cas d'échec du plugin, l'hôte se replie automatiquement sur son compilateur intégré.
- Ce plugin (R8) ne sert que la voie explicite `runtime.loadJarWithR8()` : compilation release complète avec règles keep obligatoires ; un échec est un échec, sans repli.

Les deux utilisent des protocoles totalement indépendants (`dex-compiler-api` vs `r8-compiler-api`), des actions de service, des entrées d'options développeur et des domaines de cache distincts ; aucun ne dépend de l'autre ni n'en a connaissance. Le mode `RELEASE` du protocole DEX n'est que le mode de compilation release de D8 et n'a rien à voir avec R8. Installer ou désinstaller l'un n'affecte jamais l'autre.

******

### Installation et utilisation

******

Activer le plugin prend trois étapes : installer un build AutoJs6 apparié contenant l'intégration R8, installer l'APK de ce plugin, puis sélectionner manuellement le plugin dans les options développeur d'AutoJs6. Deux choses à savoir d'emblée :

- Le plugin est inerte par défaut. Sa simple installation ne change rien dans AutoJs6 ; tant qu'il n'est pas activé, `runtime.loadJarWithR8()` échoue simplement de façon sûre (fail-closed) au lieu d'utiliser un autre compilateur.
- C'est toujours réversible. Désactivez l'entrée dans les options développeur pour revenir à l'état précédent ; rien n'a besoin d'être désinstallé.

#### Prérequis

- Un build AutoJs6 contenant l'intégration `runtime.loadJarWithR8()` (build représentatif vérifié : AutoJs6 6.8.0 (build 5276)) ; les hôtes plus anciens n'ont ni l'entrée ni l'élément d'options développeur correspondant.
- L'hôte et le plugin doivent provenir de la même source de confiance et porter la même signature ; avec des signatures différentes le plugin ne peut pas être sélectionné -- utilisez des paquets d'installation publiés (ou compilés) par paire.
- Le plugin est actuellement en préversion privée ; les installateurs proviennent de la préversion GitHub privée ou d'un build local. Obtenez-les avec l'hôte apparié.
- Si vous compilez vous-même, conservez inchangés le paquet et le composant de service fixes ci-dessous.

Les identifiants concernés sont :

```text
host package: org.autojs.autojs6
plugin package: io.github.supermonster003.autojs6.plugin.r8compiler
paired host: AutoJs6 6.8.0 (build 5276)
exact component: io.github.supermonster003.autojs6.plugin.r8compiler/io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService
```

#### Installer et activer

1. Installez ou mettez à niveau vers un build AutoJs6 apparié contenant l'intégration R8.
2. Installez l'APK de ce plugin.
3. Ouvrez AutoJs6, allez dans Paramètres > À propos de l'application et du développeur, puis effectuez un appui long sur l'icône de l'application pour entrer dans les options développeur.
4. Allez dans R8 compiler > Explicit full-release R8 provider.
5. Sélectionnez le composant de service de ce plugin (l'exact component ci-dessus) et confirmez.

Pour répéter : l'installation seule n'active jamais le plugin, et AutoJs6 ne sélectionne jamais automatiquement un provider découvert ; tant que la sélection n'est pas faite, `runtime.loadJarWithR8()` se termine toujours en échec.

#### Confirmer l'activation

De retour sur la page des options développeur, la configuration a réussi quand le résumé d'Explicit full-release R8 provider indique que `runtime.loadJarWithR8` utilise le composant de ce plugin ; un résumé contenant « disabled » ou « fails closed » signifie que l'entrée est encore désactivée.

Si le plugin n'apparaît pas dans la liste, vérifiez dans l'ordre : l'hôte est un build apparié contenant l'intégration R8 ; les noms de paquet de l'hôte et du plugin correspondent aux identifiants ci-dessus ; l'application du plugin n'est pas désactivée par le système ; les deux signatures correspondent.

Contrairement au plugin DEX, cette entrée n'a aucune ambiguïté « qui a réellement compilé » : dès que `runtime.loadJarWithR8()` retourne avec succès, l'artefact est nécessairement passé par un traitement R8 complet (dans cet appel ou dans une génération de cache déjà vérifiée).

#### Exemple de script

Placez un JAR contenant des fichiers `.class` JVM et un fichier de règles keep dans votre répertoire de scripts, puis appelez n'importe quelle surcharge de la famille d'entrées. Les règles keep ne sont pas optionnelles : R8 supprime et obfusque chaque symbole non conservé par une règle, donc une compilation sans règles produit presque à coup sûr des classes inaccessibles sous leur nom d'origine.

```javascript
"use strict";

const program = files.path("./lib/example.jar");
const keepRules = files.path("./lib/keep-rules.pro");

// keep-rules.pro (UTF-8), e.g.:
//   -keep class com.example.autojs6.R8PluginExample { public *; }

runtime.loadJarWithR8(program, [keepRules]);

// Replace this with a public class that actually exists in example.jar
// and is kept by your keep rules.
const Example = Packages.com.example.autojs6.R8PluginExample;
console.log("R8 compiler example: " + Example.answer());
```

Quand le JAR programme référence des classes de compilation qui ne s'y trouvent pas (des stubs d'API, par exemple), utilisez la surcharge à trois arguments avec un classpath ordonné :

```javascript
runtime.loadJarWithR8(
    files.path("./lib/program.jar"),
    [files.path("./lib/keep-rules.pro")],
    [files.path("./lib/compile-api-stubs.jar")],
);
```

La surcharge à cinq arguments accepte en plus des fichiers de règles consumer et leurs ordinaux propriétaires ; chaque fichier de règles consumer est lié par ordinal au JAR de classpath correspondant :

```javascript
runtime.loadJarWithR8(program, keepRuleFiles, classpathJars, consumerRuleFiles, ownerOrdinals);
```

Points clés :

- Les JAR de classpath ne servent qu'à résoudre les références à la compilation ; ils ne sont ni empaquetés dans la sortie ni chargés automatiquement. Leur ordre compte et fait partie de l'identité de cache.
- Les règles keep et consumer doivent être du texte UTF-8 strict ; les règles contenant accès au système de fichiers, include, redirection d'entrée/sortie, dictionnaire et directives dangereuses similaires sont rejetées d'emblée (fail-closed).
- Le mapping et les autres artefacts sont actuellement vérifiés et stockés dans le cache privé de l'hôte ; il n'existe pas encore d'entrée d'export côté script (voir ROADMAP).
- La compilation n'est pas un audit de sécurité ; ne chargez que des JAR de confiance.

#### Guide des règles keep

Pour des recettes minimales couvrant l'accès `Packages`, la réflexion, JNI, la sérialisation et une API publique, consultez le [guide pratique des règles keep](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/keep-rules-guide.md).

#### Que se passe-t-il en cas d'échec

La sémantique d'échec de cette entrée est volontairement simple : soit vous obtenez les artefacts R8 complets, soit une erreur -- rien entre les deux.

- Aucun provider sélectionné, arguments invalides, provider indisponible ou occupé (BUSY ; une seule session de compilation à la fois), échec de compilation, délai dépassé (120 s par défaut, 300 s au plus), échec de vérification d'artefact, échec de cache ou de chargement -- tous terminent l'appel de script en erreur R8, jamais en basculant vers D8/dx.
- Votre propre annulation (arrêter le script, par exemple) termine l'appel immédiatement et ne déclenche pas non plus de repli.
- Un hit de cache ne change pas la sémantique : les artefacts en cache ont été entièrement vérifiés à l'écriture et sont re-hachés à chaque lecture.

Si vous voulez vraiment « se replier sur une compilation brute en cas d'échec », interceptez l'erreur dans votre script et appelez `runtime.loadJar()` explicitement ; l'hôte ne prendra pas cette décision à votre place.

#### Diagnostic et signalement

Les causes les plus courantes, dans l'ordre : aucun provider sélectionné dans les options développeur ; règles rejetées (directives interdites ou encodage non UTF-8) ; entrée dépassant une limite de ressources ; un nom de classe mal orthographié ou non couvert par les règles keep (R8 a obfusqué ou supprimé les symboles non conservés). Lors d'un signalement, joignez autant que possible :

- Version et build d'AutoJs6, version du plugin, et le nom complet du composant depuis le résumé des options développeur.
- Modèle d'appareil, version Android (API) et architecture CPU (ABI).
- Le JAR programme déclencheur et tous les fichiers de règles (ou leurs tailles en octets et empreintes SHA-256), l'exception complète du script et les étapes de reproduction.

Si vous savez utiliser ADB, les commandes suivantes collectent les journaux pertinents (remplacez `<serial>` par le numéro de série de votre appareil ; retirez les chemins privés et contenus sensibles avant de partager) :

```powershell
adb -s <serial> shell dumpsys package org.autojs.autojs6
adb -s <serial> shell dumpsys package io.github.supermonster003.autojs6.plugin.r8compiler
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### Désactiver, revenir en arrière et désinstaller

- Désactivation temporaire : désactivez l'entrée sous Explicit full-release R8 provider dans les options développeur et confirmez. `runtime.loadJarWithR8()` revient à un échec sûr, rien d'autre ne change, et la sélection de composant conservée peut être réactivée à tout moment.
- Désinstaller le plugin : désactivez d'abord l'entrée, puis arrêtez AutoJs6 et désinstallez l'APK du plugin. La désinstallation supprime toutes les données et fichiers temporaires propres au plugin.
- Après une réinstallation ou une mise à jour, l'hôte revalide l'identité du composant (UID et signature compris), la sélection doit donc être confirmée à nouveau dans les options développeur.

******

### FAQ

******

**Q : Pourquoi les règles keep sont-elles obligatoires ?**

R : Le profil full-release de R8 supprime et obfusque chaque symbole non explicitement conservé. Les scripts accèdent aux classes par réflexion via `Packages.xxx`, donc R8 ne peut pas déduire quels symboles doivent survivre ; le protocole rend donc les règles keep explicitement obligatoires, évitant le piège silencieux du « compilé sans erreur, classe introuvable ».

**Q : Est-ce plus rapide que le compilateur intégré ou le plugin DEX ?**

R : Non -- généralement plus lent. R8 effectue une analyse du programme entier (shrink/optimize/obfuscate), intrinsèquement plus coûteuse qu'une compilation D8 brute ; en échange vous obtenez un artefact plus petit et plus difficile à rétro-analyser, plus un fichier mapping. Les résultats sont mis en cache, donc les chargements suivants de la même entrée sont rapides.

**Q : Peut-il remplacer le plugin DEX Compiler (ou inversement) ?**

R : Non. Ils servent des entrées de script différentes avec des protocoles et caches indépendants ; voir « Relation avec le plugin DEX Compiler ».

**Q : Pourquoi n'y a-t-il pas de repli automatique vers D8 en cas d'échec ?**

R : C'est voulu. Appeler l'entrée R8 déclare « j'ai besoin d'un artefact release complet » ; un repli silencieux vous remettrait un artefact non obfusqué à votre insu. Si vous voulez une sémantique de repli, interceptez l'erreur dans votre script et appelez `runtime.loadJar()` vous-même.

**Q : Comment désobfusquer une pile d'appels ?**

R : Chaque compilation produit un mapping et des métadonnées retrace, que l'hôte vérifie et met en cache ; la version actuelle n'a pas encore d'entrée côté script ou interface pour récupérer le mapping, et un RPC retrace est sur la feuille de route. Quand vous compilez l'artefact vous-même, utilisez l'outil retrace de R8 avec le mapping que vous avez conservé pour restaurer les piles.

**Q : Le plugin accède-t-il au réseau ou lit-il mes fichiers ?**

R : Non. Il n'a aucune permission réseau ou stockage, lit l'entrée de compilation uniquement depuis les descripteurs de fichiers transmis par AutoJs6, ne voit jamais de chemins de fichiers sur le canal, et garde ses fichiers temporaires strictement dans son propre répertoire privé.

**Q : À quoi sert l'interface du lanceur ?**

R : L'écran en lecture seule du plugin affiche sa version, la version R8 épinglée, la disponibilité du composant de service et le journal des modifications inclus. Il n'active pas le fournisseur ; sa sélection et son activation restent exclusivement dans les options développeur d'AutoJs6.

******

### Limites du périmètre

******

Pour éviter tout malentendu, les points suivants sont explicitement hors du périmètre de ce plugin:

- Ne sert que `runtime.loadJarWithR8()` ; il ne modifie jamais `runtime.loadJar()` ni `runtime.loadJarWithClasspath()`, et ces entrées ne peuvent pas le sélectionner implicitement.
- Pas de mode D8, pas de compilation debug, pas d'interrupteurs individuels pour shrink/optimize/obfuscate : le profil est fixé à FULL_RELEASE.
- Aucune découverte implicite de règles dans les JAR (comme les fichiers proguard sous META-INF) ; les règles keep et consumer doivent être fournies explicitement avec la requête.
- Les règles contenant accès au système de fichiers, include, redirection d'entrée/sortie, import de mapping, print, dictionnaire ou directives de contrôle global de profil sont rejetées (hors protocole 1.0, fail-closed).
- Pas de RPC retrace ; les métadonnées retrace ne servent qu'à la provenance du mapping, et le mapping n'a pas encore d'export côté script (les deux figurent sur la ROADMAP).
- Pas de téléchargement ni de résolution de dépendances (pas d'intégration Maven/Gradle), pas de compilation réseau.
- Aucune prise en charge de `.aar`, de `.dex` précompilés ou du bytecode dynamique `defineClass()` ; ceux-ci empruntent toujours les voies intégrées d'AutoJs6.
- Distribution privée seulement pour l'instant : le code source et les installateurs résident dans un dépôt GitHub privé ; la publication publique est un élément séparé de la feuille de route.

******

### Référence technique

******

Ce qui suit s'adresse aux développeurs et intégrateurs qui ont besoin de limites exactes ; les utilisateurs ordinaires du plugin peuvent généralement l'ignorer.

#### Entrée et sortie

Le protocole 1.0 reçoit un bundle d'entrée canonique via un descripteur en lecture seule et renvoie un bundle d'artefacts canonique via un descripteur en écriture seule ; aucun chemin de fichier ne transite jamais sur le canal, et l'entrée entière ainsi que chaque artefact sont liés à un SHA-256:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: R8 8.13.17
profile: FULL_RELEASE (shrink + optimize + obfuscate)
```

#### Identifiants de découverte du plugin

L'hôte découvre et appelle le plugin via les identifiants suivants:

```text
service action: org.autojs.plugin.R8_COMPILER
plugin id: r8-compiler
protocol provider id: autojs6-r8
engine: r8-compiler
variant: r8
protocol: 1.0–1.1
api namespace: org.autojs.plugin.r8compiler.api
distribution: org.autojs.plugin.r8compiler:r8-compiler-api:0.2.0
cache domain: autojs6:r8-compiler:v1
```

Le plugin déclare R8 8.13.17, le protocole 1.0, le profil fixe FULL_RELEASE, `minApi` 24 à 36, la sortie multi-dex et l'ensemble de capacités à cinq artefacts. La bibliothèque de compilation est une bibliothèque de plateforme Android 36 embarquée et vérifiée octet par octet ; l'empreinte runtime-library reste liée aux fichiers boot-classpath observés sur l'appareil.

Le plugin ne contient aucune bibliothèque native et couvre toutes les ABI d'appareils avec un unique APK universel pur JVM ; les appels JNI vérifiés visent les méthodes natives des JAR compilés, pas le plugin lui-même.

#### Modèle de sécurité

Le plugin ne demande aucune permission réseau ou stockage. Le service de compilation est protégé par la permission `org.autojs.permission.PLUGIN` et s'exécute dans le processus dédié `:r8` ; chaque appel vérifie le nom de paquet, l'UID de l'appelant et les deux signatures dans les deux sens, n'acceptant que l'hôte AutoJs6 de même signature. L'entrée et la sortie ne circulent que sous forme de descripteurs de fichiers ; les descripteurs avec de mauvais modes d'accès ou dont l'entrée/sortie pointe vers le même point de terminaison sont rejetés. Les fichiers temporaires restent dans l'espace de travail privé du plugin, et les espaces périmés sont récupérés automatiquement. L'hôte revérifie également chaque artefact de manière indépendante, et le chargeur de classes n'accepte qu'une copie DEX en lecture seule re-hachée.

#### Limites de ressources

Pour se défendre contre les entrées malveillantes ou anormales, le protocole fixe des limites strictes à chaque étape ; les requêtes hors limites sont rejetées d'emblée:

- JAR programme : jusqu'à 128 MiB ; classpath : jusqu'à 32 JAR, 64 MiB chacun, 128 MiB au total.
- Fichiers de règles : jusqu'à 16 fichiers keep et 32 consumer ; 256 KiB par fichier, 2 MiB de règles au total, 16 KiB par ligne.
- Bundle d'entrée entier jusqu'à 260 MiB ; expansion d'archive : jusqu'à 20000 entrées par JAR et 60000 au total, 512 MiB décompressés ; données de classes jusqu'à 8 MiB par classe et 256 MiB au total.
- Bundle de sortie jusqu'à 256 MiB : DEX ZIP jusqu'à 192 MiB, mapping jusqu'à 32 MiB, seeds et usage jusqu'à 16 MiB chacun, métadonnées retrace jusqu'à 256 KiB.
- Concurrence : exactement une session de compilation à la fois ; les autres requêtes reçoivent un BUSY réessayable. Délai par défaut 120 s, plafond 300 s.
- Les diagnostics sont plafonnés à 64 KiB et expurgés des chemins.

#### Mises en garde

- `minApi` est un paramètre de compilation, pas une déclaration d'exécution d'appareil ; un artefact ne peut pas être chargé sur des appareils sous son `minApi`.
- La version de R8 est épinglée (actuellement R8 8.13.17) ; l'identité de cache inclut les empreintes du compilateur et du runtime, donc une mise à niveau du compilateur ne réutilise jamais de résultats périmés.
- L'annulation empêche immédiatement la publication du résultat, mais le travail CPU interne de R8 peut continuer dans le processus isolé jusqu'au retour de la compilation ; le slot de session reste BUSY jusqu'à la fin du nettoyage.
- La même entrée se reproduit à l'octet près sous la même version du compilateur (les gates de release ont vérifié la reproductibilité en environnement identique), mais l'identité au niveau octet entre versions de R8 n'est pas promise.
- La version de R8 embarquée par AGP affichée dans la bannière de build de l'hôte appartient à la chaîne d'empaquetage APK et n'a aucun rapport avec la version du compilateur de ce plugin.

******

### Feuille de route

******

Le développement avance par gates vérifiables : G1 gel du contrat, G2 implémentation du provider, G3 intégration hôte, G4 corpus de compatibilité, G5 release locale signée, G6 acceptation sur appareil, G7 clôture ART/JNI/Retrace et G8 release distante privée sont tous terminés, chacun avec des preuves révisables liées à des SHA-256. Les plans à venir (publication publique, disponibilité du retrace, docs et UX, maintenance des mises à niveau du moteur) et la définition de fin de chaque élément se trouvent dans :

- [Ouvrir le ROADMAP.md à cocher](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/ROADMAP.md)

******

### Historique des versions

******

# v0.2.2

###### 2026/09/19

* `Correctif` Avertissements de lecture SDK XML v4 avec AGP 9.1 et contrôles d'alignement natif des APK déclenchés par erreur lors de l'assemblage des tests unitaires JVM, avec les plugins de compilation partagés 1.8.3
* `Amélioration` compileSdk et targetSdk passent à 37 (Android 17) ; le comportement du plugin ne dépend pas de la nouvelle cible

# v0.2.1

###### 2026/09/13

* `Amélioration` Ressources traduites cohérentes, activation explicite du plugin et validation des paquets de publication

# v0.2.0

###### 2026/09/13

* `Amélioration` La vérification de compilation rejette les dépendances natives involontaires et produit un rapport JSON
* `Amélioration` Ressources traduites cohérentes, activation explicite du plugin et validation des paquets de publication

##### Autres versions

* [CHANGELOG-fr.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/assets/doc/CHANGELOG-fr.md)

******

### Build

******

```powershell
.\gradlew.bat :app:assembleDebug
```

Build de version:

```powershell
.\gradlew.bat :app:assembleRelease
```

La compilation requiert JDK 17 ou ultérieur (21 recommandé) et Android SDK Platform 36 : le script de build vérifie octet par octet `platforms/android-36/android.jar` et l'embarque comme ressource de bibliothèque de compilation, en s'interrompant à la moindre différence. minSdk actuel : 24, targetSdk : 37.

L'ABI du protocole provient des AAR de contrat 0.1.0 gelés dans le dépôt (sous `plugin-api/r8-compiler-api/releases/0.1.0/`) ; l'application consomme ces octets d'AAR plutôt que leurs projets sources:

```text
protocol-wire-api-0.1.0.aar
r8-compiler-api-0.2.0.aar
```

Le compilateur est tiré de Maven en tant que R8 8.13.17 épinglé. Les releases officielles utilisent les scripts de publication et de vérification sous `scripts/` (répertoire de release local append-only, builds reproductibles à deux instantanés et vérification par gate) ; pour le débogage quotidien, les commandes Gradle ci-dessus suffisent.

******

### Licence

******

Le code source du projet est sous licence MPL-2.0. R8 et les autres composants tiers restent sous leurs propres licences.

******

### Organisation des ressources

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

`.python/generate_markdown.py` génère le README et le CHANGELOG dans les 10 langues (y compris README.md et CHANGELOG.md à la racine du dépôt) à partir de sources JSON ; pour modifier la documentation, éditez les sources JSON plutôt que le Markdown généré.

Pour vérifier que chaque fichier Markdown généré correspond à sa source sans modifier l'espace de travail, exécutez:

```powershell
python .python/generate_markdown.py --check
```

******

### Liens

******

- Documentation AutoJs6: https://docs.autojs6.com
- Projet R8: https://r8.googlesource.com/r8
- Plugin DEX Compiler (D8): https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler
- Page de release privée (accès requis): https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases/tag/v0.1.0-provider-dev-private.1


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/16kb.md)

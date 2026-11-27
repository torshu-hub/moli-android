# PIÈGES — ce qui a déjà fait trébucher un agent

À lire en début de mission. **Seulement ce qui fait perdre du temps à l'agent quand il travaille** : compilation, tests, chemins, outils, Git, CI. Un comportement de Moli ou d'Android n'a pas sa place ici (il va dans le code, ETAT.md ou la doc). Une ligne par piège : la cause, puis la parade. Chaque mission ajoute celui qu'elle a rencontré et retire ceux devenus faux. Court, sinon personne ne le lit.

## Compiler et tester
- `web/dist` absent : `moli-api` ne compile pas (`Dist::get` introuvable). → `mkdir -p web/dist`, ou passer par `scripts/check.sh` qui le crée.
- Première compilation longue (plus de 10 min). → Tester seulement le paquet touché (`cargo test -p moli-android`) ; la CI fait le reste (PROJET : pas de contrôle en double).
- Tests Matter vendus en échec dans le conteneur (erreur 97, pas d'IPv6). → Propre au conteneur, rien à corriger.
- Test officiel `a_valve_turned_by_hand_is_left_as_it_is_for_a_while` (moli-heating) rouge **tous les soirs de 22 h à minuit UTC** (23 h à 1 h en France l'hiver) : le maintien de 2 h qu'il attend est coupé au prochain pas du programme ou à minuit (reproduit en local sur `main`). → Relancer avant minuit UTC ne sert à rien : attendre minuit UTC, ne pas toucher au code officiel.
- Clippy pédant refuse un sigle sans backticks dans la doc (`UPnP`, `SSDP`…). → Les écrire entre backticks.
- Pas de SDK Android dans le conteneur : le Java ne se compile pas en local. → Le job `apk` le vérifie ; contrôle rapide possible avant : `javac` contre `com/google/android/android/4.1.1.4` et `org/json/json` (Maven Central), un `R.java` généré et des stubs des classes voisines (ignorer les API plus récentes que ce jar : `isDestroyed`, `setElevation`…).
- Le conteneur ne joint pas `dev.netatmo.com` ni `api.netatmo.com` (403 du proxy). → Lire l'API par pyatmo et l'intégration Home Assistant (`raw.githubusercontent.com/jabesq-org/pyatmo`, `…/home-assistant/core/dev/homeassistant/components/netatmo`).
- Un Cargo.lock qui n'a pas le nouveau crate : `--locked` échoue. → `cargo metadata` après avoir ajouté un crate ou une dépendance (`--offline` échoue si le registre n'est pas encore en cache), et committer le Cargo.lock.
- `leak-check.sh` refuse toute adresse MAC sauf `aa:bb:cc:dd:ee:NN` (même dans une fixture). → Les anonymiser ainsi.

## Git, GitHub, APK
- Les PR sont fusionnées en squash : la branche locale diverge toujours de `main` ensuite (`git pull --ff-only` échoue), et la poussée forcée est refusée à l'agent. → Repartir de `origin/main` (`git checkout -B <branche> origin/main`), puis fusionner l'ancienne branche distante en gardant la version de `main` (`git fetch origin <branche> && git merge -s ours FETCH_HEAD`).
- ETAT.md modifié en parallèle par une autre conversation : conflit. → Récupérer `main` juste avant d'éditer ETAT.md, et avant de fusionner.
- Numéro d'APK : c'est le `run_number` du workflow `android` lancé par le push sur `main`, pas celui de la PR (les PR consomment aussi des numéros).
- Chaque push sur `main` (même une fusion de documentation) publie une nouvelle APK numérotée et **retire la précédente** : le lien `moli-<numéro>.apk` donné avant la PR de documentation devient mort. → Donner le numéro du dernier run `android` de `main`, après la dernière fusion, une fois ce run terminé.
- Moli officiel (`rudy-io/moli-os`, public) : `add_repo` demande l'accord de l'utilisateur, mais git lit directement. → `git remote add upstream https://github.com/rudy-io/moli-os && git fetch upstream main`, puis `git merge upstream/main` (sans conflit quand l'officiel n'a pas bougé là où on a touché).
- `create_pull_request` ne rend pas toujours le numéro de la PR. → `list_pull_requests` avant `subscribe_pr_activity` ; ne jamais le deviner.
- Relancer un job en échec (`rerun_failed_jobs`) est refusé (403) tant que le run tourne encore. → Attendre la fin du run ; lire le journal d'un job par son `job_id` (`get_job_logs`).

# PIÈGES — ce qui a déjà fait trébucher un agent

À lire en début de mission. Une ligne par piège : la cause, puis la parade. Chaque mission ajoute celui qu'elle a rencontré et retire ceux devenus faux. Court, sinon personne ne le lit.

## Compiler et tester
- `web/dist` absent : `moli-api` ne compile pas (`Dist::get` introuvable). → `mkdir -p web/dist`, ou passer par `scripts/check.sh` qui le crée.
- Première compilation longue (plus de 10 min). → Tester seulement le paquet touché (`cargo test -p moli-android`) ; la CI fait le reste (PROJET : pas de contrôle en double).
- Tests Matter vendus en échec dans le conteneur (erreur 97, pas d'IPv6). → Propre au conteneur, rien à corriger.
- Test officiel `a_valve_turned_by_hand_is_left_as_it_is_for_a_while` (moli-heating) rouge selon l'heure. → Relancer le job une fois, ne pas toucher au code officiel.
- Clippy pédant refuse un sigle sans backticks dans la doc (`UPnP`, `SSDP`…). → Les écrire entre backticks.
- Pas de SDK Android dans le conteneur : le Java ne se compile pas en local. → Le workflow `android` de la PR le vérifie (job `apk`).

## Moli et Android
- L'API de Moli répond 403 sans en-tête `Host` connu (garde contre le DNS rebinding). → `Host: 127.0.0.1:<port>` (`plugs.rs`, `host()`).
- Avec un VPN, la route par défaut est celle du VPN. → Adresse et box lues sur le lien Wi-Fi/Ethernet (`Setup.homeAddress`, `Setup.homeGateway`).
- `/proc/net/arp` illisible pour une appli depuis Android 10 : pas de MAC, pas de fabricant par préfixe MAC. → Ne pas construire dessus.
- Un appareil ajouté qu'on retire garde sa mémoire dans le hub (`<instance>:<uuid>` : définition, valeurs, nom) ; réécrire `devices-added.json` à la main libère le numéro sans elle et le suivant hérite d'une fiche fantôme. → Retirer par la route officielle `DELETE /api/integrations/devices/{instance}`, jamais en écrivant le fichier.
- La garde : `protected_devices` demande aussi le code au tableau de bord ouvert sans code (origine « foyer »). → Vérifier comment la tablette ouvre le tableau de bord avant de protéger un appareil.

- Le conteneur ne joint pas `dev.netatmo.com` (403 du proxy). → Lire l'API par pyatmo et l'intégration Home Assistant (`raw.githubusercontent.com/jabesq-org/pyatmo`, `…/home-assistant/core/dev/homeassistant/components/netatmo`).
- Un Cargo.lock qui n'a pas le nouveau crate : `--locked` échoue. → `cargo metadata --offline` après avoir ajouté un crate ou une dépendance, et committer le Cargo.lock.
- `leak-check.sh` refuse toute adresse MAC sauf `aa:bb:cc:dd:ee:NN` (même dans une fixture). → Les anonymiser ainsi.
- Le moteur n'écrivait aucun journal sur Android (aucun abonné `tracing`). → `journal.rs` en installe un au démarrage ; ne pas en ajouter un second.

## Git, GitHub, APK
- Les PR sont fusionnées en squash : la branche locale diverge toujours de `main` ensuite (`git pull --ff-only` échoue), et la poussée forcée est refusée à l'agent. → Repartir de `origin/main` (`git checkout -B <branche> origin/main`), puis fusionner l'ancienne branche distante en gardant la version de `main` (`git fetch origin <branche> && git merge -s ours FETCH_HEAD`).
- ETAT.md modifié en parallèle par une autre conversation : conflit. → Récupérer `main` juste avant d'éditer ETAT.md, et avant de fusionner.
- Numéro d'APK : c'est le `run_number` du workflow `android` lancé par le push sur `main`, pas celui de la PR (les PR consomment aussi des numéros).
- Pas de vérification de secours programmée (PROJET) : les événements de la PR suffisent.
- Accès à l'ancien fork (`moli-os-android`) refusé à une session. → Ne pas en dépendre ; le dire dans le rapport.
- Lire l'officiel (`rudy-io/moli-os`, PR #331 comprise) : `add_repo` est refusé par la permission de l'outil tant que le propriétaire ne l'a pas autorisée ; une fois autorisé, le dépôt est public : `GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 …` hors du projet, puis `git fetch --depth=1 origin pull/<n>/head:pr<n>`.

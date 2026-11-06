# ETAT — Moli OS Android

- **Jalon** : mission 1, le socle Android (moteur officiel hébergé + écran de gestion).
- **Branche / PR** : `claude/pensive-brahmagupta-mbs5xd`, PR brouillon torshu-hub/moli-android#2.
- **Décisions actives** : DECISIONS.md ; hébergement JNI repris de `moli-os-android@moli-beta2-code-24`, sans porte cookie ; identifiant `io.moli.android` (validé) ; écoute `0.0.0.0:8790` comme l'officiel (validé, accès depuis les autres appareils voulu) ; nouveau certificat de signature (validé). Protection de l'API sur le réseau local : en attente d'arbitrage sur le constat de la PR, rien d'ajouté.
- **Preuves** : scripts/check.sh vert (dont le test `hosted`) ; CI check, audit et android vertes ; l'APK contient le moteur et ses 4 points d'entrée JNI ; constat réseau mesuré le 6 nov. (PR#2). Pas encore : APK signée, essai sur la tablette.
- **Prochaine action** : le propriétaire crée le certificat, pose le secret `ANDROID_SIGNING_BUNDLE`, relance `android`, essaie l'APK sur la Tab S5e.

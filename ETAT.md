# ETAT — Moli OS Android

- **Jalon** : mission 1, le socle Android (moteur officiel hébergé + écran de gestion).
- **Branche / PR** : `claude/pensive-brahmagupta-mbs5xd`, PR brouillon torshu-hub/moli-android#2.
- **Décisions actives** : DECISIONS.md ; hébergement JNI repris de `moli-os-android@moli-beta2-code-24`, sans la porte cookie (à arbitrer, PR#2 point 4) ; identifiant proposé `io.moli.android`.
- **Preuves** : scripts/check.sh vert (dont le test `hosted` : démarre, répond, s'arrête, redémarre, sans `master.key`) ; CI check, audit et android vertes ; APK construite par la CI (essai debug + non signée). Pas encore : APK signée (secret `ANDROID_SIGNING_BUNDLE` absent), essai sur la tablette.
- **Prochaine action** : le propriétaire pose le secret, relance `android`, essaie l'APK sur la Tab S5e.

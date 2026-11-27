---
name: livrer
description: Livrer une mission de Moli OS Android — PR, CI, fusion dans main, puis numéro et lien de l'APK. À utiliser quand le travail est prêt et que le propriétaire attend la livraison. Ne remplace pas PROJET.md : il ne fait que dérouler les étapes.
---

# Livrer

Les règles sont dans PROJET.md (« Travailler », « Accords ») ; les pièges Git/CI dans PIEGES.md. Ici, seulement l'ordre des gestes.

1. **Avant d'éditer ETAT.md** : `git fetch origin main`, puis le relire s'il a bougé (conflit sinon). Mettre ETAT.md à jour dans la même PR : état actuel, pas un journal.
2. **Contrôle** : `sh scripts/check.sh` si le Rust ou le catalogue a changé ; rien d'autre que la CI ne refait.
3. **PR** : pousser la branche de mission, `create_pull_request` (rapport court : ajouté, retiré, écarts avec Moli officiel, ce qui reste à essayer sur la tablette). Le numéro ne revient pas toujours : `list_pull_requests`, ne jamais le deviner. Puis `subscribe_pr_activity`.
4. **CI** : attendre les événements de la PR, sans interroger. Rouge = lire le journal du job (`get_job_logs`), corriger, repousser. Un test officiel rouge selon l'heure : voir PIEGES.
5. **Fusion** (squash) par toi, quand la CI est verte et le rapport publié. Ensuite repartir de `origin/main` (PIEGES : la branche diverge après un squash).
6. **APK** (seulement si le code de l'appli a changé) : la fusion lance le workflow `android` ; son `run_number` est le numéro de l'APK. Donner au propriétaire le numéro et le lien `https://github.com/torshu-hub/moli-android/releases/download/apk/moli-<numéro>.apk`, cliquable, puis le même lien dans un bloc à copier.
7. **ETAT.md** : si la fusion change l'état (APK, PR, « à essayer »), une petite PR de documentation, même méthode.
8. **Compte rendu final**, une fois la PR de documentation fusionnée : le dire, redonner le numéro de l'APK, son lien cliquable et le même lien dans un bloc à copier, puis les essais à faire sur la tablette, numérotés.

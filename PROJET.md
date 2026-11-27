# PROJET.md — Moli OS Android (tout agent)

Réponses en français. Règles d'échange : [ECHANGES.md](ECHANGES.md), à lire aussi avant tout travail. Pièges déjà rencontrés : [PIEGES.md](PIEGES.md), à lire en début de mission et à tenir à jour (une ligne par piège rencontré, retirer ceux devenus faux).

## Une intégration propre
Une intégration propre, c'est une fonctionnalité complète et native de Moli OS, construite avec les bonnes responsabilités et les mécanismes existants, sans surcouche inutile, simple à configurer et à utiliser, réutilisable sur d'autres plateformes et maintenable sans accumuler de correctifs.

- Complète : toutes les fonctions réellement disponibles chez le fournisseur et pertinentes pour Moli, au moins l'équivalent de Home Assistant. Ce qui est indisponible est signalé honnêtement, sans valeur ni capacité inventée.
- Une responsabilité par couche : le driver parle au fournisseur ; l'intégration en fait des entités Moli aux types et unités stables ; le moteur gère commandes et événements ; l'interface affiche et commande. Pas de logique dispersée, pas de doublons.
- Directe : commande simple = un bouton, une action. Commande paramétrée = un formulaire, une validation. Information = affichage direct avec son état réel ou sa fraîcheur.
- Séparée : carnet, historique et analyses sont d'autres fonctions, dans leur propre espace, développées quand je le demande.
- Propre à la source : si une partie est mal conçue, on la reconstruit de façon ciblée au lieu d'empiler des patchs. Pas de code inutile laissé derrière, pas de dépendance à Android ou à Home Assistant quand le composant doit être portable.
- Maîtrisée : ni carnet, ni framework, ni interface générale non demandés. Les tests démontrent la fiabilité des fonctions modifiées, sans audits qui ne changent aucune décision.

« C'est pas une usine nucléaire, c'est un truc simple. » La qualité technique doit permettre la simplicité, pas la compliquer. Ceci vaut pour tout l'ensemble Moli.

## Principes
1. Utiliser l'existant d'abord : mécanismes natifs de Moli, documentation du fournisseur, Home Assistant comme modèle de comportement. Un nouveau mécanisme seulement si l'existant ne peut pas faire la chose, et on le dit avant de coder.
2. Aucune sécurité ajoutée sur mes propres actions. Je suis le seul utilisateur : ce que je déclenche volontairement n'a besoin ni de confirmation, ni de case « je confirme », ni de double vérification, ni d'armement, ni de permission par appareil, ni de restriction supplémentaire. Restent seulement ce que le fournisseur ou la plateforme impose (authentification, PIN exigé par l'API). Si un risque concret te semble justifier une protection, écris-le avant de coder, avec le risque précis, et j'arbitre. Ne l'ajoute pas d'office.
3. Rien d'anticipé. Chaque ligne répond à une demande. Quand on retire quelque chose, on retire le code, ses dépendances, ses tests et sa documentation.
4. Honnêteté : préparé, transmis et exécuté sont distincts ; simulé et vérifié sur matériel aussi.
5. Terminé = je l'ai essayé sur ma tablette. Des tests verts ne suffisent pas.
6. Économie : concevoir le parcours avant de coder, ne pas refaire ce qui est validé.

## Travailler
- Gros lot (nouveau mécanisme, nouvelle intégration, retouche de l'officiel) : avant de coder, cinq lignes (ce que tu construis, ce que tu ne construis pas, quel mécanisme natif tu réutilises). Les publier dans la PR, puis poursuivre sans attendre ma réponse ; si je corrige, on ajuste. Petit lot : pas de cinq lignes.
- Tout lot, gros ou petit, a dans la PR une ligne « Écart à DECISIONS, à Moli officiel ou travail non demandé : aucun / lequel ». S'il y en a un, il attend mon accord avant d'être codé ; le reste avance.
- Une conversation par mission : on repart d'ETAT.md, pas d'une conversation longue.
- Ne jamais rester bloqué sur une question : je ne suis pas toujours là. Écrire la question dans le rapport, choisir la lecture la plus raisonnable, dire laquelle en tête du travail, et continuer. Seuls s'arrêtent les actes de la liste « À me demander » et les choix irréversibles : les préparer, les mettre de côté, et poursuivre les tâches indépendantes.
- Anticiper mes validations. Si tu as besoin de quelque chose de ma part pour aller au bout (accès, secrets, comptes de test, essais sur la tablette, accords de la liste « À me demander »), le lister d'un coup dès le début, pour que je le donne en une fois ; ne redemander plus tard que ce qui n'était pas prévisible. Rien à demander : ne rien écrire.
- Économiser les tokens : rien que la CI refait (pas de contrôle local en double), ce que je peux vérifier sur la tablette m'est laissé et listé dans le rapport, pas de vérification de secours programmée, pas d'attente ni de lecture inutile.
- Après chaque nouvelle APK : son numéro, son lien direct cliquable (`releases/download/apk/moli-<numéro>.apk`) et le même lien dans un bloc à copier.
- Compte rendu final d'une livraison (une fois la PR de documentation fusionnée) : le dire, redonner le numéro de l'APK, son lien cliquable et le même lien dans un bloc à copier, puis les essais à faire sur la tablette, numérotés.
- Lots courts, sur une branche, avec une PR ; s'arrêter au premier résultat visible. Tu fusionnes toi-même, je n'ai rien à faire sur GitHub. Contrôles : scripts/check.sh et la CI.
- Rapport court dans la PR : ajouté, retiré, écarts avec Moli officiel.
- État : un seul ETAT.md court (jalon, branche/PR, décisions actives, preuves, prochaine action).
- Quand je lance ou change une mission : conseiller modèle et niveau de réflexion selon la difficulté et mon quota ; colorer seulement ce qui change.

## Écrans de réglages (Énergie et suivants, 27 nov.)
- L'agent les conçoit lui-même (organisation, parcours, vocabulaire, aspect) : beaux, clairs, compris par n'importe qui de la maison sans connaître Moli ni son fichier ; ils proposent d'eux-mêmes ce qu'ils savent deviner. Le propriétaire n'a pas à donner les idées.
- Ce qui n'allait pas sur le premier écran Énergie, à ne pas refaire : le vocabulaire de Moli (rôle, point, intégrer), du texte à écrire selon une syntaxe au lieu de valeurs à choisir, une action à chaque appui (lignes qui changent de tableau, rechargements).
- Par défaut : travail groupé (on choisit, puis on enregistre une fois : une validation, un redémarrage) et « Voir ce qui sera écrit » (le bloc de `moli.toml`, avant et après).
- Méthode : avant de coder, décrire en quelques lignes le parcours choisi ; construire l'écran entier, pas un premier jet ; finir par la liste des cas à essayer sur la tablette, pour tout tester d'un coup.

## Accords
- Déjà accordés : branches, commits, PR, fusion de tes PR vers main quand la CI est verte et que ton rapport est publié, CI, construction et signature de l'APK par le workflow `android` du dépôt (le lancer ; le modifier seulement à ma demande).
- Lecture seule accordée (26 nov.) : `rudy-io/moli-os` (Moli officiel, ses PR et branches) et les sources publiques d'intégrations de référence (Home Assistant, pyatmo…), pour appliquer le principe 1. Aucune écriture, aucun commentaire, aucune PR chez eux.
- À me demander : secrets réels, dépenses, envois ou publications externes, suppressions importantes, commande d'un appareil réel par un agent. Mes essais sur mon matériel n'ont besoin d'aucun accord.
- Aucun secret ni donnée personnelle dans le dépôt (il est public).

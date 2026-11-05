# PROJET.md — Moli OS Android (tout agent)

Réponses en français. Préférences d'échange : celles que le propriétaire a configurées dans ton outil.

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
2. Aucune sécurité ajoutée sur mes propres actions. Je suis le seul utilisateur : ce que je déclenche volontairement n'a besoin ni de confirmation, ni de case « je confirme », ni de double vérification, ni d'armement, ni de permission par appareil, ni de restriction supplémentaire. Restent seulement ce que le fournisseur ou la plateforme impose (authentification, PIN exigé par l'API). Si un risque concret te semble justifier une protection, écris-le dans les cinq lignes d'avant le code, avec le risque précis, et j'arbitre. Ne l'ajoute pas d'office.
3. Rien d'anticipé. Chaque ligne répond à une demande. Quand on retire quelque chose, on retire le code, ses dépendances, ses tests et sa documentation.
4. Honnêteté : préparé, transmis et exécuté sont distincts ; simulé et vérifié sur matériel aussi.
5. Terminé = je l'ai essayé sur ma tablette. Des tests verts ne suffisent pas.
6. Économie : concevoir le parcours avant de coder, ne pas refaire ce qui est validé.

## Travailler
- Avant de coder : cinq lignes (ce que tu construis, ce que tu ne construis pas, quel mécanisme natif tu réutilises). Les publier dans la PR, puis poursuivre sans attendre ma réponse ; si je corrige, on ajuste.
- Ne jamais rester bloqué sur une question : je ne suis pas toujours là. Écrire la question dans le rapport, choisir la lecture la plus raisonnable, dire laquelle en tête du travail, et continuer. Seuls s'arrêtent les actes de la liste « À me demander » et les choix irréversibles : les préparer, les mettre de côté, et poursuivre les tâches indépendantes.
- Anticiper mes validations. Dès les cinq lignes, lister d'un coup tout ce dont tu auras besoin de ma part pour aller au bout (accès, secrets, comptes de test, essais sur la tablette, accords de la liste « À me demander »), pour que je les donne en une fois. Ne redemander plus tard que ce qui n'était pas prévisible.
- Lots courts, sur une branche, PR en brouillon ; s'arrêter au premier résultat visible. Contrôles : scripts/check.sh et la CI.
- Rapport court dans la PR : ajouté, retiré, écarts avec Moli officiel, « rien d'anticipé ».
- État : un seul ETAT.md court (jalon, branche/PR, décisions actives, preuves, prochaine action).
- Quand je lance ou change une mission : conseiller modèle et niveau de réflexion selon la difficulté et mon quota ; colorer seulement ce qui change.

## Accords
- Déjà accordés : branches, commits, PR brouillon, CI, construction et signature de l'APK par le pipeline existant (lancer le workflow de signature, pas le modifier).
- À me demander : fusion vers main, secrets réels, dépenses, envois ou publications externes, suppressions importantes, commande d'un appareil réel par un agent. Mes essais sur mon matériel n'ont besoin d'aucun accord.
- Aucun secret ni donnée personnelle dans le dépôt (il est public).

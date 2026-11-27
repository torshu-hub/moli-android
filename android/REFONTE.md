# Refonte de l'écran Android — conception (27 nov. 2026)

Référence de la mission « refonte complète de l'interface Android et de toute la configuration
de Moli ». Chaque lot s'y tient ; une correction du propriétaire s'écrit ici. Règles : PROJET.md
(« Écrans de réglages »), DECISIONS 12.

## Pour qui, pour quoi

Quelqu'un de la maison, sur la tablette, sans connaître Moli ni `moli.toml`. Il règle sa maison
(pièces, chauffage, lumières…) comme dans l'appli d'une maison connectée, pas comme un
opérateur. L'écran écrit les fichiers officiels (`moli.toml`, coffre, `data/home.json`) : la
maison reste portable (Raspberry Pi). Ce que le tableau de bord fait déjà bien n'est pas refait :
nommer un appareil et le ranger dans une pièce, ajouter par adresse, prix du kWh, programmes de
chauffage, automatismes, plan, réglages de l'alarme.

## Navigation

Un accueil (`ManagementActivity`), puis un écran par sujet. Pas de menu caché : tout se voit
d'un coup d'œil, par groupes, chaque tuile dit son état en une ligne (« 6 pièces · 2 étages »,
« Pas encore réglé », « 3 intégrations, une ne répond pas »).

| Groupe | Écrans (fichiers écrits) |
|---|---|
| En-tête | état de Moli, son adresse sur le réseau (pour les téléphones), **Ouvrir Moli**, arrêter / démarrer |
| Appareils | Intégrations (`[[driver]]`, coffre) · Recherche réseau |
| La maison | Pièces et affichage (`home.json`) · Chauffage (`[[heating]]`, `[heating_house]`) · Lumières (`[[fixture]]`) · Scènes et rythme (`[[scene]]`, `[rhythm]`, `home.json` `scenes`) · Dehors et soleil (`[outdoor]`, `[sun]`, position de la maison) · Énergie (`[energy]`) |
| Sécurité et accès | Protection (`[guard]`) · Assistants IA (`[mcp]`) · Accès et serveur (`[server]`, `[server.access]`) |
| Assistant | Moli l'assistant (`[assistant]`, fournisseurs, clé au coffre) |
| Cette tablette | Sauvegarder · Restaurer · Clé et code · Journal de Moli · Ouvrir Moli au lancement |

Un écran n'apparaît sur l'accueil que lorsqu'il est livré (rien d'annoncé « bientôt »).

## Vocabulaire

Les mots de la famille, jamais ceux du fichier : « pièce », « étage », « dehors », « masqué »,
« sans bouton » (pas `quiet`), « capteur de la pièce » (pas `sensor`), « vanne » (pas
`valves`), « programme », « absence », « protégée », « heures calmes ». Un identifiant n'est
jamais tapé : on choisit un appareil ou une mesure dans une liste qui dit son nom, sa pièce et sa
valeur du moment. Les identifiants à créer (`id`) sont dérivés du nom et ne changent plus.

## Un écran de réglages (modèle commun)

- En-tête : retour, titre, une phrase qui dit à quoi sert l'écran ; à droite **Voir ce qui sera
  écrit** (le bloc du fichier, avant et après, lecture seule, et le refus éventuel de Moli).
- Contenu en cartes ; deux colonnes sur la tablette (≥ 720 dp), une sur un téléphone.
- **Rien n'est écrit avant « Enregistrer »**. Une barre en bas n'apparaît qu'avec des
  modifications : « 3 modifications · Annuler · Enregistrer ». Un enregistrement = une
  vérification par le moteur officiel, puis un redémarrage de Moli (`moli.toml`) ou aucun
  (`home.json`). La page ne bouge pas, rien ne saute d'une liste à l'autre.
- Retour avec des modifications : Enregistrer / Abandonner / Rester.
- **Moli propose** : ce que l'écran sait deviner (une pièce nommée par les appareils et absente,
  le capteur de température d'une pièce, la vanne voisine…) arrive en suggestion, un appui
  l'ajoute au brouillon.
- **Sans effet visible** : un réglage que Moli ne montrera nulle part dans l'état actuel de la
  maison est signalé à côté de lui, en une ligne ambre, avec ce qui manque.
- Moli arrêté : l'écran montre ce qui est écrit et le dit ; les listes d'appareils attendent Moli.

Composants communs (`Look`) : page, carte, ligne (icône, titre, détail, valeur, chevron),
interrupteur, pastilles de choix (une ou plusieurs), compteur « − valeur + », plages horaires à
l'horloge, sélecteur d'appareil ou de mesure (recherche, filtre par pièce, choix multiple), barre
d'enregistrement, bandeau, aperçu, état vide. Icônes : Material Symbols Rounded (Apache 2.0) en
`res/drawable`, teintées dans le code.

## Architecture

**Java : vues construites en code, gardées.** Pas de XML de mise en page, ni AndroidX, ni Compose,
ni bibliothèque Material : pas de SDK dans le conteneur, et chaque dépendance ajoute du temps de
CI, du poids et des contraintes de thème pour des écrans qui sont des formulaires. Ce qui change :
un petit kit (`Look`) pour tous les écrans, une classe de base `SettingsScreen` (brouillon,
modifications, enregistrer, aperçu, retour) écrite avec son premier écran, et un contrôle local du
Java contre l'API 34 (PIEGES) en plus du job `apk`.

**Rust (`crates/moli-android`) : une couche par fichier.**
- `moli.toml` : un module générique de sections (lire des sections en JSON, remplacer des
  sections entières en gardant les autres et leurs commentaires avec `toml_edit`, aperçu avant /
  après). La vérification est **celle de Moli** : le fichier candidat passe par
  `Config::load_for_recovery` (lecture et contrôles officiels, sans démarrer) avant d'être écrit ;
  puis redémarrage et retour à l'ancien fichier s'il refuse (mécanisme des Intégrations). Seul
  ajout côté moteur : une fonction `hosted::check`, dans `hosted.rs` (fichier d'hébergement déjà
  propre à l'appli). Aucun changement du code officiel.
- `home.json` : lu et écrit dans le dossier de données, en écriture atomique (comme
  `layout_edit.rs` officiel), clés inconnues gardées, sans redémarrer (`/api/home` le relit à
  chaque appel).
- Les appareils, pièces et mesures de la maison : `/api/devices` de Moli, lu une fois par écran.
- Secrets : coffre officiel seulement.

## Ordre des lots

1. **Accueil** : la navigation de toute la refonte, groupes et résumés vivants, adresse de Moli
   sur le réseau, Journal accessible depuis l'accueil, icônes. Tout le reste s'y accroche.
2. **Pièces et affichage** (`home.json`) : pièces (ordre, icône, étage, dedans / dehors, autres
   noms ; « Moli propose » les pièces que nomment les appareils), accueil du tableau de bord (nom
   de la maison, lumières, raccourcis, clims, caméras, portes surveillées), masqués, sans bouton,
   apparence par défaut (thème, place et forme du menu). En premier parce que les pièces servent
   à tous les autres écrans. Apporte `SettingsScreen` et le sélecteur d'appareils (`Picker`).
   Pas ici : les noms des sorties de multiprise (le crayon du tableau de bord les donne déjà), la
   position de la maison (lot 5, avec le soleil).
3. **Chauffage** : pièces chauffées (capteur, vannes, réglage fin replié), programmes de la
   maison, absence. Fort usage l'hiver, uniquement dans le fichier aujourd'hui. Apporte le module
   générique de sections et `hosted::check`.
4. **Lumières** puis **Scènes et rythme** (les scènes visent les luminaires).
5. **Dehors et soleil** (stations, prévision, façades, position de la maison : `location` de
   `home.json`, qui prime sur `moli.toml`).
6. **Protection** et **Assistants IA** (`[guard]`, `[mcp]`). La question Hyundai
   (`protected_devices` sur le verrouillage) reste à la mission Hyundai : l'écran offre la liste,
   il ne protège rien de lui-même.
7. **Assistant** (`[assistant]`, fournisseurs, clé).
8. **Accès et serveur** (`[server]` : fuseau, langue, noms d'accès, historique, Cloudflare
   Access, miroir). `listen` et `data_dir` sont tenus par l'appli : montrés, pas modifiables.
9. **Intégrations, Recherche réseau, Énergie** passés sur le kit (aspect, composants communs ;
   Énergie : avertissements « sans effet visible », vocabulaire). En dernier : ils marchent déjà.

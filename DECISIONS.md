# DECISIONS — Moli OS Android

Décisions du propriétaire, 5 et 6 novembre 2026. Les règles de travail sont dans AGENTS.md.

1. **Base.** Nouveau fork propre de `rudy-io/moli-os`. Reprise sélective depuis l'ancien fork (`torshu-hub/moli-os-android`, tag `moli-beta2-code-24`) : l'hébergement Android (JNI, service, clé protégée par le Keystore) et le profil Meross MSS310. Le reste de l'ancien fork sert seulement de référence de protocole.
2. **Rôles.** Claude Code cadre et exécute dans le dépôt, sans relais ChatGPT pour Moli. À réévaluer après 2 ou 3 missions.
3. **Application neuve.** Les données de l'ancienne version ne sont pas reprises. Réutiliser le certificat de signature existant si cela simplifie (à confirmer avec le propriétaire avant toute copie de secret).
4. **Lancement.** Un écran de gestion Android d'abord, car il n'y a pas de terminal sur Android : configuration des intégrations et administration du serveur, sans second moteur ni page qui double le tableau de bord. Le tableau de bord Moli s'ouvre depuis cet écran. Plus tard : un paramètre « lancement direct sur Moli ».
5. **Hyundai.** Configuration comme dans Home Assistant : région (EU), e-mail, mot de passe, PIN, saisis une fois et rangés dans le coffre. L'intégration choisit seule le transport. Aucun PIN à chaque commande. Commandes : l'équivalent de Home Assistant. GPS : oui, avec une seule bascule à la configuration.
6. **Netatmo.** Connexion durable, renouvelée sans refaire le lien chaque jour (aujourd'hui : coller l'URL de redirection tous les jours). Objectif : se rapprocher de Home Assistant. Avant de choisir une solution, étudier comment Home Assistant s'y prend.
7. **Meross.** Récupération de la clé via le compte, comme `meross_lan`.
8. **Tests.** `scripts/check.sh` et la CI seulement. Journal exportable de débogage : fonction envisagée plus tard (l'ancienne n'était pas au point), pas dans le premier lot.
9. **Découverte des appareils.** Voulue, comme dans Home Assistant : les intégrations retrouvent seules le matériel. On utilise le mécanisme de Moli officiel (retrouvailles par mDNS, SSDP, adresse MAC) avant d'écrire quoi que ce soit. L'agent vérifie ce qu'il couvre pour Meross, Netatmo et Hyundai. Pas de découverte réseau propre à l'application (en Java) tant que l'officiel suffit ; s'il ne suffit pas, il le dit avant de coder.
10. **Carnet Ioniq 5 et historique.** Plus tard, comme pages à part, quand le propriétaire le demande.

# Onboarding : Netatmo

1. Sur <https://dev.netatmo.com/apps/>, créer une application (ou ouvrir la vôtre) et noter son `client_id` et son `client_secret`.
2. Dans cette application, déclarer comme adresse de retour (`Redirect URI`) : `http://localhost:8791/netatmo`.
3. **Sur Android** : saisir `client_id` et `client_secret`, laisser `refresh_token` vide, toucher « Se connecter à Netatmo », accepter dans la page Netatmo. La connexion se renouvelle seule ensuite.
4. **Sur un ordinateur ou un Raspberry Pi** : sur la page de l'application, générer un jeton avec les portées `read_thermostat write_thermostat read_station read_homecoach`, puis ranger les trois secrets par un tube (jamais en clair) :
   - `read -rs V; printf %s "$V" | docker compose exec -T moli-os /moli-os secrets set netatmo client_id; unset V`
   - `read -rs V; printf %s "$V" | docker compose exec -T moli-os /moli-os secrets set netatmo client_secret; unset V`
   - `read -rs V; printf %s "$V" | docker compose exec -T moli-os /moli-os secrets set netatmo refresh_token; unset V`
5. Déclarer `[[driver]]` avec `id = "netatmo"` et `kind = "netatmo"` dans `data/moli.toml`, puis redémarrer Moli. Option : `home_id` pour ne lire qu'une maison du compte.

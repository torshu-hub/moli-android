# Onboarding : Heures creuses par horaires

1. Relever les heures creuses du contrat (facture, ou espace client du fournisseur), par
   exemple `22:00-06:00`, ou deux plages `01:30-07:30` et `12:30-14:30`.
2. **Sur Android** : écran Énergie → « Facture et tarif » : prix des heures pleines, des
   heures creuses, et la case « Heures creuses ». L'écran crée cet appareil et le relie au
   tarif.
3. Sinon, dans `moli.toml` :

   ```toml
   [[driver]]
   id = "tarif"
   kind = "offpeak"
   [driver.options]
   offpeak = ["22:00-06:00"]

   [energy.tariff]
   period = "tarif:periode/period"
   prices = { HP = 0.2081, HC = 0.1635 }
   ```

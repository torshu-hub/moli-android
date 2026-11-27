# Heures creuses par horaires

Un appareil `<instance>:periode` dont le point `period` vaut `HP` ou `HC`, selon les
heures creuses du contrat, pour une maison dont le compteur ne dit pas la période (pas de
lecteur Linky). Recalculé chaque minute d'après l'horloge de la maison : juste dès que
Moli démarre.

`[energy.tariff] period = "<instance>:periode/period"` : chaque heure est comptée au prix
de sa période, et une charge pilotée se complète en « HC ». Un compteur qui dit la période
(Linky TIC) reste préférable : il suit les changements d'horaires d'Enedis tout seul.

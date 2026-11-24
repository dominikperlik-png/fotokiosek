<?php
// Zkopíruj jako config.php (vedle index.php) a změň kód. Je potřeba jen pro založení prvního účtu.
// Kód: aspoň 8 znaků, nikomu ho nedávej. Po založení účtu ho můžeš ze souboru smazat.
const SETUP_CODE = 'ZMEN-MNE-na-dlouhy-nahodny-kod';

// Jen když web běží za reverzní proxy (nginx proxy, Cloudflare…): IP adresa proxy. Pak se limit
// přihlašování počítá podle skutečné IP z X-Forwarded-For, ne podle proxy.
// const TRUSTED_PROXY = '127.0.0.1';

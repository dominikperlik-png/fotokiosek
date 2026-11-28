<?php
// Zkopíruj jako config.php (vedle index.php) a změň kód. Je potřeba jen pro založení prvního účtu.
// Kód: aspoň 8 znaků, nikomu ho nedávej. Po založení účtu ho můžeš ze souboru smazat.
const SETUP_CODE = 'ZMEN-MNE-na-dlouhy-nahodny-kod';

// Jen když web běží za reverzní proxy (nginx proxy, Cloudflare…): IP adresa proxy. Pak se limit
// přihlašování počítá podle skutečné IP z X-Forwarded-For, ne podle proxy.
// const TRUSTED_PROXY = '127.0.0.1';

// Záloha fotek na Google Disk (nepovinné): údaje z Google Cloud Console (návod navod-google-disk.md).
// Adresa pro přesměrování v Google Cloud = adresa tohoto webu + /drive.php. Nikomu je neposílej.
// const GOOGLE_CLIENT_ID = '….apps.googleusercontent.com';
// const GOOGLE_CLIENT_SECRET = 'GOCSPX-…';

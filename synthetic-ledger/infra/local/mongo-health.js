const fs = require('fs');
const admin = db.getSiblingDB('admin');
if (!admin.auth('dtp_admin', fs.readFileSync('/run/secrets/mongo-admin.password', 'utf8').trim())) quit(1);
quit(admin.runCommand({ping: 1}).ok === 1 ? 0 : 1);

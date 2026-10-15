const fs = require('fs');
const password = fs.readFileSync('/run/secrets/mongo-admin.password', 'utf8').trim();
const connection = new Mongo('mongodb://mongo:27017/?directConnection=true');
const admin = connection.getDB('admin');
if (!admin.auth('dtp_admin', password)) throw new Error('Local Mongo administrative authentication failed');
function requireOk(result, operation) {
  if (result.ok !== 1) throw new Error(operation + ' failed, code ' + (result.code || 'unknown'));
}
if (admin.runCommand({hello: 1}).setName !== 'rs0') {
  requireOk(admin.runCommand({replSetInitiate: {_id: 'rs0', members: [{_id: 0, host: 'mongo:27017'}]}}), 'Local replica initialization');
}
const deadline = Date.now() + 90000;
while (!admin.runCommand({hello: 1}).isWritablePrimary) {
  if (Date.now() > deadline) throw new Error('Local replica primary readiness timeout');
  sleep(500);
}
const activity = connection.getDB('dtp_activity');
if (!activity.getUser('dtp_activity')) {
  requireOk(activity.runCommand({createUser: 'dtp_activity', pwd: fs.readFileSync('/run/secrets/mongo-activity.password', 'utf8').trim(), roles: [{role: 'readWrite', db: 'dtp_activity'}]}), 'Scoped Activity identity creation');
}
print('Local Mongo primary and scoped Activity identity ready');

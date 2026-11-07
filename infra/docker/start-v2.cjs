const databaseUrl = new URL(process.env.DATABASE_URL);
databaseUrl.username = process.env.DB_USERNAME;
databaseUrl.password = process.env.DB_PASSWORD;
process.env.DATABASE_URL = databaseUrl.toString();

require('./dist/main.js');

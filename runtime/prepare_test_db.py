import os,psycopg
db=psycopg.connect(os.environ['DB_URL'].replace('jdbc:',''),user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'],autocommit=True)
assert db.execute('select current_database()').fetchone()[0]=='java_ai_v2'
if not db.execute("select 1 from pg_database where datname='java_ai_v2_test'").fetchone():db.execute('create database java_ai_v2_test')
print('Isolated V2 test database ready')

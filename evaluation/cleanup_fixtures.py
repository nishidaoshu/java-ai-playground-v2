"""Remove only disposable EV orders named in selected evaluation result manifests.
Retains seeded demo orders, real browser acceptance records, documents, chats and traces.
"""
import argparse,json,re,os
from pathlib import Path
import psycopg
parser=argparse.ArgumentParser();parser.add_argument('reports',nargs='+');args=parser.parse_args()
ids=set()
for report in args.reports:ids.update(json.loads(Path(report).read_text(encoding='utf-8')).get('fixtureOrderIds',[]))
assert all(re.fullmatch(r'EV[0-9A-F]{12}',value) for value in ids),'Invalid fixture manifest'
connection=psycopg.connect(os.environ['DB_URL'].replace('jdbc:',''),user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'])
assert connection.execute('select current_database()').fetchone()[0]=='java_ai_v2'
ids=sorted(ids)
before=connection.execute("select id,status,version from flight_orders where id like 'A100_' or id like 'B100_' order by id").fetchall()
if ids:
    with connection.transaction():
        connection.execute('delete from operation_records where order_id=any(%s) or plan_id in(select id from operation_plans where order_id=any(%s))',(ids,ids))
        connection.execute('delete from operation_plans where order_id=any(%s)',(ids,))
        connection.execute('delete from flight_orders where id=any(%s)',(ids,))
    connection.commit()
after=connection.execute("select id,status,version from flight_orders where id like 'A100_' or id like 'B100_' order by id").fetchall()
assert before==after,'Seed order changed during cleanup'
print('Removed evaluation fixtures only:',len(ids),'manifest order IDs; seeded demo orders unchanged')

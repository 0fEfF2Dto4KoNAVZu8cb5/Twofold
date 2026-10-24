"""Small shared rounds: posting first invites a reply, rather than a lock ping-pong."""
import json
import uuid


def preferences(store):
    with store.connect() as conn:
        row = conn.execute("SELECT value FROM settings WHERE key='share_to_reveal'").fetchone()
    return {'share_to_reveal': row is None or row[0] == 'true'}


def join(conn, slot, post_id, received_at):
    """Called in the post's IMMEDIATE transaction, so simultaneous uploads join once."""
    current = conn.execute('SELECT * FROM rounds ORDER BY seq DESC LIMIT 1').fetchone()
    members = [] if current is None else conn.execute('SELECT slot FROM round_members WHERE round_id=?', (current['id'],)).fetchall()
    if current is None or len(members) == 2:
        ident = str(uuid.uuid4())
        conn.execute('INSERT INTO rounds(id,created_at) VALUES (?,?)', (ident, received_at))
    else:
        ident = current['id']
    conn.execute('INSERT INTO round_members(round_id,slot,post_id,submitted_at) VALUES (?,?,?,?) '
                 'ON CONFLICT(round_id,slot) DO UPDATE SET post_id=excluded.post_id,submitted_at=excluded.submitted_at',
                 (ident, slot, post_id, received_at))
    return ident


def current(store, slot):
    with store.connect() as conn:
        row = conn.execute('SELECT * FROM rounds ORDER BY seq DESC LIMIT 1').fetchone()
        if row is None:
            return None
        members = {r['slot']: r['post_id'] for r in conn.execute('SELECT slot,post_id FROM round_members WHERE round_id=?', (row['id'],))}
    return {'id': row['id'], 'created_at': row['created_at'], 'own_posted': slot in members,
            'partner_posted': 3 - slot in members, 'complete': len(members) == 2,
            'waiting_for_you': slot not in members and 3 - slot in members}


def locked(store, row, viewer):
    row = dict(row)
    ident = row.get('round_id', '')
    if viewer == row['slot'] or not ident or not preferences(store)['share_to_reveal']:
        return False
    with store.connect() as conn:
        return conn.execute('SELECT 1 FROM round_members WHERE round_id=? AND slot=?', (ident, viewer)).fetchone() is None


def visible(store, row, viewer):
    if row is None:
        return None
    result = store.public(row)
    result['round_id'] = dict(row).get('round_id', '')
    result['locked'] = locked(store, row, viewer)
    if result['locked']:
        # No original caption, EXIF, GPS or camera context before participating.
        result['metadata'] = {'author_name': json.loads(row['metadata'])['author_name']}
    return result

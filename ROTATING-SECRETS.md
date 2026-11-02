# Guide: changing the Moqui passwords and keys in production

**Status:** this is a plan and a guide. Nothing described here has been run on any server. Wherever something could not be proven from the code, it says **NOT PROVEN** and tells you how to check it before you rely on it.

**Who this is for:** the person who runs the production deployment (with the `ansible-ars` project), including someone who has never looked inside Moqui.

**Related document:** `ansible-ars/docs/ips-mis-rotate-passwords.md` covers the *other* parts of the stack (the main Postgres admin password, the message broker "Artemis", the vault). This guide covers everything that belongs to **Moqui**.

---

## At a glance: every secret and how easy it is to rotate

Can a password be changed without a special database step? **Mostly no.** The only changes with no SQL at all are Moqui user passwords (done in the Moqui screen); their salts are renewed with them. The database passwords need one `ALTER ROLE` command. The encryption key is the hardest.

**Ease:** 🟢 easy, 🟡 needs one extra step, 🔴 hard or risky.

| Secret | Where the real value lives | What you must do besides vault and `.env` | Ease |
|---|---|---|---|
| **Moqui user passwords** (`john.hardy`, `executor`, staff, ...) | Moqui database, as hash plus per-user salt | Change in the Moqui UI as admin. No SQL. | 🟢 |
| **Per-user salts** | Same row as the hash | Nothing. Each password change creates a new salt. | 🟢 |
| `INTEGRATION_MOQUI_USER_PWD` | A Moqui user (default `john.doe`) | Change it in Moqui first, then vault, `.env`, recreate `integration`. The wrong order locks the account. | 🟡 |
| `entity_ds_password` | PostgreSQL role `moqui` | One `ALTER ROLE`, then `.env`, then recreate Moqui. | 🟡 |
| `DB_CLOSURE_USER_PWD` | PostgreSQL role `cnt_operator` | One `ALTER ROLE`, then recreate Moqui and `integration`. The install script created this role with a hardcoded password, so the real value may not match `.env`: test the login first. | 🟡 |
| `POSTGRES_PASSWORD` | PostgreSQL role `postgres` | One `ALTER ROLE`. The image only reads `.env` when the volume is first created. | 🟡 |
| Login keys / sessions | Moqui database and memory | Optional `DELETE FROM user_login_key` and a Moqui restart. Otherwise keys expire after 6 days. | 🟡 |
| `JMS_USER_PWD` | PostgreSQL role `jms`, also baked into the jms image | `ALTER ROLE` and a rebuild of the jms image. | 🔴 |
| `ACTIVEMQ_USER_PWD`, `ACTIVEMQ_OPERATOR_PWD` | Inside the jms image (Artemis, not Moqui itself) | Rebuild the image. The operator one is not passed to the container, and the integration app has a hardcoded `jms_password`. | 🔴 |
| `entity_ds_crypt_pass` | Not used. The real key is hardcoded in `MoquiProdConf.xml` and the compose file. | Switch the key, then re-enter the encrypted secrets (SMTP password etc.). Changing only the `.env` value makes them unreadable. | 🔴 |
| `crypt-salt` | Not in `.env` at all; the default `20201202` applies | Part of the same key. Needs a new variable and a config change. | 🔴 |

**Suggested start:** Moqui user passwords first (largest risk: the public password `moqui`; no SQL), then the three Moqui database roles (`moqui`, `cnt_operator`, `postgres`). Leave the jms/Artemis secrets and the crypt key for later; count the encrypted data first (section 7.2), it may show the crypt key is a minor issue.

Details: Part 2 (user passwords), Part 1 (database roles), Part 3 (login keys), Part 4 (crypt key). Jms/Artemis are covered in `ansible-ars/docs/ips-mis-rotate-passwords.md`.

---

## 0. The short version

Moqui uses four kinds of secrets. They are stored in different places, so each one is changed in a different way. **You cannot change them all by editing one file.**

| Part | What it is | In plain words | Where the real value lives | Changed by |
|---|---|---|---|---|
| **1** | Database passwords | The passwords Moqui uses to log in to PostgreSQL | Inside PostgreSQL | A SQL command, then the `.env` file, then restart |
| **2** | User passwords and their salts | The passwords people (and the integration program) use to log in to Moqui | Inside the Moqui database | Moqui's own "change password" function |
| **3** | Login keys and sessions | "Remember me" tokens that let someone in without typing the password | Inside the Moqui database and in memory | Delete them, restart Moqui |
| **4** | The encryption key (`crypt-pass` and `crypt-salt`) | The key that locks the few secrets Moqui stores for itself (for example the e-mail password) | Nowhere; it is only the key | Switch the key **and** re-enter the locked secrets |

**The two things that surprise people most:**

1. **The `.env` file is only a delivery note.** For database passwords, the real password is inside PostgreSQL. If you only change the `.env` file, Moqui will try the new password and PostgreSQL will refuse it. Moqui will then fail to start.
2. **The encryption key in `.env` is not even used today.** The real key is written directly in a config file (`MoquiProdConf.xml`) and in the compose file. Changing the `.env` value does nothing. And changing the real key without a plan makes the stored secrets unreadable.

You do **not** have to do everything at once. Parts 1, 2 and 3 are normal and safe. Part 4 is the delicate one; section 6 helps you decide whether and when to do it.

---

## 1. Words used in this guide

- **Role** (PostgreSQL): a database login account, for example `moqui`.
- **Hash**: a one-way scramble of a password. Moqui never stores your real password, only the scrambled version. It cannot be un-scrambled, only compared.
- **Salt**: a short random piece of text mixed into the password before scrambling, so two users with the same password get different hashes. In Moqui **every user has their own salt**, stored next to their hash in the database. There is no single "password salt" for the whole system.
- **`crypt-pass` and `crypt-salt`**: a *different* thing. Together they form the **key** that locks (encrypts) a handful of secrets that Moqui must be able to read back later, such as the SMTP (e-mail) password. This is a single, system-wide key.
- **`.env`**: the text file on the server holding settings that docker-compose passes into the containers. The Ansible project writes it from the vault.
- **Vault**: the Ansible vault, where the real secret values are kept.
- **Maintenance window**: a short period where users are told the system may be unavailable.

---

## 2. Rules for new secrets (read before you generate anything)

1. **Generate them on your own machine and put them straight into the Ansible vault.** Never paste them into chat, e-mail, tickets, or a terminal command line (command lines are saved in shell history and are visible to other users of the server).
2. **Database passwords:** 40 characters, letters and digits only (`A-Za-z0-9`). Reason: they pass through XML files, shell scripts and `.env` readers, and symbols like `$ & # " ' \` or spaces break one of them. Generator that shows nothing on screen:
   `openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c 40`
3. **Moqui user passwords:** Moqui enforces its own rule (at least 8 characters, at least one digit and at least one character that is neither a letter nor a digit). So a letters-and-digits-only value is **rejected**. Use the 40 characters from above **plus one safe symbol at the end, `-` or `_`** (for example `...abc123-`). Never use the unsafe symbols listed above.
4. **The encryption salt (`crypt-salt`):** only the **first 8 characters** are used. Generate 8 letters/digits.
5. **One secret, one value.** Never reuse the same value for two purposes, and never share a value between test and production.
6. **Treat every old default as already public:** `moqui`, `admin`, `operator`, `some-passw0rd`, `MoquiDefaultPassword:...`. They are in the repository and in the documentation. Replace them all.

---

## 3. Before you start (checklist)

Do all of this before touching anything. If one box cannot be ticked, stop.

- [ ] **Do the whole guide on the test environment first**, from start to finish, and write down any step that behaved differently.
- [ ] **Announce a maintenance window.** Moqui is restarted several times. Plan about 10 to 20 minutes of unavailability per part. Part 4 can be done on another day.
- [ ] **Take a full database backup** and check that the file exists and has a sensible size. The Ansible repo has `custom/ips/scripts/91-pg-dump.sh`. Copy the file off the server.
- [ ] **Keep copies of the current `.env` and the current `customer/configs/moqui/conf/MoquiProdConf.xml`.** They are your way back.
- [ ] **Open two terminals/browser sessions as an administrator** and keep one logged in to Moqui throughout. If something goes wrong with logins, that open session still works until it expires.
- [ ] **Write down which names apply on this server** (they differ per installation):
  - the PostgreSQL container name (called `<postgres-container>` below; on the test host it is `pg-ips`, the compose variable is `POSTGRES_CONTAINER`),
  - the Moqui container name (`<moqui-container>`),
  - the Moqui database name (`moqui_app_db` in the install scripts) and Moqui database role (`moqui`),
  - the extra "closure" database and role (`DB_CLOSURE_NAME` and `DB_CLOSURE_USER`; the install script creates role `cnt_operator` on database `ips_controlling_data`).
- [ ] **Check the real current state.** The names and the passwords on the server may not match the templates in the repository. Section 4.1 shows how.

---

## 4. Part 1: Database access passwords

### 4.1 What is involved

Moqui connects to PostgreSQL with these accounts:

| Account | Used for | Setting that Moqui reads | Vault/`.env` name |
|---|---|---|---|
| `moqui` (variable `entity_ds_user`) | The main Moqui database | `entity_ds_password` | `vault_env_entity_ds_password` |
| closure role, normally `cnt_operator` (variable `DB_CLOSURE_USER`) | The second database with the customer's data (also used by the integration program) | `DB_CLOSURE_USER_PWD` | `vault_env_db_closure_user_pwd` |

The other database accounts (`postgres` admin, `jms`) are changed according to the Ansible document named at the top. The steps are the same as below.

**Important facts that explain every step:**

- The password is stored **inside PostgreSQL**. The `.env` file merely tells the containers what to type.
- The install scripts (`02-system.db.sh`, `03-data.cust.db.sh`) create these accounts **only when the database does not yet exist**. They never reset a password later. So re-running an install does not rotate anything.
- **A known mismatch to check first:** the install script `03-data.cust.db.sh` creates `cnt_operator` with a fixed password written into the script (`some-passw0rd`), **not** with the `.env` value. So on a server built from these scripts the real password of that account may differ from `DB_CLOSURE_USER_PWD` in `.env`. Find out before you change anything (4.2).
- Changing a password in PostgreSQL does **not** disconnect sessions that are already open. Running containers keep working until they are restarted, which is why a restart order is given below.

### 4.2 Step A: find out what is true right now (read only, changes nothing)

Check that the password currently in `.env` really works for each account. Use the same value Moqui uses:

```bash
# on the production host; the password is read from .env by the shell, not typed on the command line
set -a; . /path/to/.env; set +a
docker exec -e PGPASSWORD="$entity_ds_password" <postgres-container> \
    psql -h 127.0.0.1 -U "$entity_ds_user" -d "$entity_ds_database" -c 'select 1;'
docker exec -e PGPASSWORD="$DB_CLOSURE_USER_PWD" <postgres-container> \
    psql -h 127.0.0.1 -U "$DB_CLOSURE_USER" -d "$DB_CLOSURE_NAME" -c 'select 1;'
```

- Prints `1` and `(1 row)`: the `.env` value is the real password. Continue.
- `password authentication failed`: `.env` is **not** the real password (for example the closure role still has the script's fixed password). That is fine for a rotation, because you are about to overwrite it anyway, but note it in the change record and make sure you have PostgreSQL superuser access (the `postgres` role, or the container's local trust login) before you continue.

*(`-h 127.0.0.1` forces a real password check. Without it the official image may skip the password inside the container and the test proves nothing.)*

### 4.3 Step B: prepare the new values

1. Generate two new 40-character values (section 2, rule 2).
2. Put them into the Ansible vault under the names in the table in 4.1 (`ansible-vault edit ...`).
3. Do **not** write the `.env` yet.
4. Create a temporary file **on the production host**, owner root, readable only by root, which holds the two SQL statements. Use a text editor on the server, so the passwords never appear in a command line:

   `/root/rotate-db.sql` (permissions `0600`):
   ```sql
   ALTER ROLE moqui PASSWORD 'NEW_VALUE_FOR_MOQUI';
   ALTER ROLE cnt_operator PASSWORD 'NEW_VALUE_FOR_CLOSURE_ROLE';
   ```
   Use the real role names from your checklist (they are written here as in the install scripts).

### 4.4 Step C: the change (short downtime starts here)

Do these in order. Between steps 2 and 5 Moqui cannot start with the old `.env`, so do them without long pauses.

1. **Stop** the Moqui container and every other container that logs in with these accounts (the `integration` container uses the closure role; `py-calc`/other apps if they use it, **NOT PROVEN** which: search the compose files of that host for `DB_CLOSURE_USER_PWD`).
2. **Change the passwords in PostgreSQL:**
   ```bash
   docker exec -i <postgres-container> psql -U postgres -v ON_ERROR_STOP=1 < /root/rotate-db.sql
   ```
   Expected: `ALTER ROLE` printed twice. If the `postgres` role asks for a password, use the one from the vault; do not put it on the command line.
3. **Securely delete the file:** `shred -u /root/rotate-db.sql`
4. **Write the new `.env`** with the Ansible script (`05-write-env.sh`; run its preview first. The preview hides secrets). The `.env` on the host must now contain the new values.
5. **Start** (recreate) the containers: Moqui first, then the integration container.
6. Watch the Moqui log until it says it has started (`docker logs -f <moqui-container>`).

### 4.5 Step D: prove it worked

- [ ] Moqui web page opens and you can log in.
- [ ] The Moqui log has **no** `password authentication failed` or `FATAL` lines.
- [ ] A normal action works that reads and writes data (open a record, save a harmless change, or run the application's health check).
- [ ] The integration container's routes work (one route runs and finishes).
- [ ] The **old** password no longer works (repeat the test from 4.2 with the old value; it must fail).

### 4.6 If it fails

| Symptom | Likely cause | Fix |
|---|---|---|
| Moqui stops on start with a password error | `.env` and PostgreSQL disagree | Put the value you just set in PostgreSQL into the vault and rewrite `.env`, or set PostgreSQL back with `ALTER ROLE ... PASSWORD '<old value>';` (you still have the old value in the vault history) and start again |
| Moqui works, integration does not | The integration container was not recreated, or still has the old closure password | Recreate it |
| Everything fails and you cannot log in to PostgreSQL | The `postgres` password is not what you think | Use the container's local trust login (`docker exec -it <postgres-container> psql -U postgres`) from the host; this is allowed for a root user on the host |

**Rollback of this part:** `ALTER ROLE <name> PASSWORD '<old value>';` for both roles, restore the previous `.env`, recreate the containers.

---

## 5. Part 2: User passwords and their salts

### 5.1 What is involved (in plain words)

Every person who can log in to Moqui is a row in the table `user_account`. Next to each row Moqui keeps:

- a **hash** of the password (the scrambled password),
- that user's **own salt** (random text, different for each user),
- the **hash type** (for example `SHA` or `SHA-256`).

Also, the **integration program** logs in as one of these users (variable `INTEGRATION_MOQUI_USER`, password `INTEGRATION_MOQUI_USER_PWD`).

**You do not rotate salts by hand.** The Moqui "change password" function creates a **new random salt every time a password is set**. So if you change a user's password with Moqui's own function, that user's salt is renewed automatically. It also replaces an old weak hash type with `SHA-256`.

**What is wrong today:** the starting accounts in the seed database (`john.hardy`, `john.doe`, `executor`) have **no salt at all**, use the old weak `SHA` type, and the password is the public word `moqui`. Anyone who has read the repository can log in to a fresh install. These accounts must be changed or switched off.

Three more places keep traces of the old password and must be cleaned (done in 5.3):

- the **password history** (the last 5 old hashes with their salts, so the old `moqui` hash is still stored),
- **login keys** (Part 3),
- **the record of failed logins**, which may contain mistyped passwords.

### 5.2 Step A: list the accounts (read only)

On the Moqui database (replace names as in your checklist). This prints no passwords or hashes:

```bash
docker exec -i <postgres-container> psql -U postgres -d moqui_app_db <<'SQL'
SELECT user_id, username, password_hash_type,
       (password_salt IS NOT NULL AND password_salt <> '') AS has_salt,
       password_set_date, disabled
FROM user_account
ORDER BY username;
SQL
```

For **each row** decide one of three actions and write it down:

1. **Rotate**: someone or something really uses this account (for example the integration user, real staff).
2. **Disable**: nobody needs it. Seed accounts such as `executor` and any extra `john.doe` usually belong here. Disabling is safer than keeping an account with a "new" password nobody remembers.
3. **Leave alone**: the system placeholder `_NA_` must stay; it cannot be used to log in.

Note which **programs** use which account: the integration program is the known one. Search for others (the Ansible `host_env_vars`, the `proxy`, `qaf-engine` and your own apps) for any stored Moqui login. **NOT PROVEN** that there are none; the search done for this guide found only the integration container.

### 5.3 Step B: change one password at a time

Work in this order: the administrator you will use for the rest, then the integration user, then everyone else.

For each account to **rotate**:

1. **Generate the new password** (section 2, rule 3: 40 characters plus `-` or `_`), keep it in the vault.
2. **In Moqui, as an administrator, set the user's password.** Use the Moqui administration screen for user accounts (find the user, choose Change Password). An administrator is allowed to set another user's password without knowing the old one. Behind the scene this runs Moqui's `update#Password` function, which creates the new salt, stores the new hash, marks the type `SHA-256`, and puts the old hash in the history.
   - If the screen says the password is too weak, check the symbol rule (section 2, rule 3).
   - **NOT PROVEN:** the exact menu path in your Moqui version (it differs by installation). Try it on the test server first and add the real menu path to this guide.
   - **NOT PROVEN:** whether calling the function from a script (`/rest` or `/rpc`) is allowed on production. The screen is the supported way until someone checks.
3. **If it is the integration user:** now, without delay, update the vault value `vault_env_integration_moqui_user_pwd`, write the `.env`, recreate the `integration` container. Until you do, the integration program keeps using the old password and **will be refused**. Note: Moqui locks an account for 5 minutes after 3 wrong attempts, so a forgotten integration container locks its own account over and over. That is a clear sign you missed a consumer.
4. **Test the login with the new password**, in a private browser window (leave your admin session alone). Then check that the old password is refused.

For each account to **disable**: set the account to disabled in the Moqui user screen (do not delete it; other tables point at it).

### 5.4 Step C: clean the leftovers

Do this after the passwords are changed, still inside the maintenance window. Use one transaction for the first two statements so you can check before committing. Replace `'<user_id>'` with the id of each *rotated* user, or leave the condition out to clean for everyone (acceptable, because every account was just rotated or disabled):

```sql
BEGIN;
-- old hashes and their salts of the rotated users (includes the old public "moqui" hash)
DELETE FROM user_password_history WHERE user_id IN ('<user_id>', '<another_user_id>');
-- wrongly typed passwords recorded at failed logins
UPDATE user_login_history SET password_used = NULL WHERE password_used IS NOT NULL;
COMMIT;
```

What you lose by deleting the history: Moqui can no longer refuse "you used this password before". That is meaningless for a freshly generated random password.

### 5.5 Step D: prove it worked

Run the list from 5.2 again. For each rotated, enabled account you should see:

- `password_hash_type` = `SHA-256`,
- `has_salt` = `true`,
- `password_set_date` = today.

Also:

- [ ] Every rotated user can log in with the new password and **cannot** with the old one.
- [ ] `john.hardy`, `john.doe` and `executor` cannot log in with `moqui`.
- [ ] The integration program logs in (a route runs).
- [ ] You still have a working administrator.

### 5.6 If it fails

- **An administrator is locked out or has forgotten the new password:** use the break-glass route, which another administrator can do through the same screen. If there is no other administrator, you must write the hash straight into the database (discouraged: it skips the checks; test it on a copy first, **NOT PROVEN** exact steps). For this reason *always* create or keep a second administrator before rotating the first.
- **Wrong password set for a user:** simply set it again with the same screen.

### 5.7 Problem for the future: new installations start with `moqui`

Every new environment is built from the seed database, so it starts with the public `moqui` passwords again. Until this is automated, the first thing after any new installation must be this Part 2. The build task `replaceUserPasswordInDump` only changes **one** user and does it with **no salt**, so it does not solve this.

---

## 6. Part 3: Login keys and open sessions

### 6.1 What it is

Besides a password, Moqui lets a client log in with a **login key** (a long random token sent as `api_key` or `login_key`). Moqui stores only a scrambled copy of the key, in the table `user_login_key`, and by default a key expires after 6 days. Anyone who holds a key stays logged in after you change the password, until the key expires. Open browser sessions are also not ended by a password change.

### 6.2 Steps

1. **Look first** (counts only, no secrets):
   ```sql
   SELECT user_id, count(*) AS keys, max(thru_date) AS latest_expiry
   FROM user_login_key GROUP BY user_id;
   ```
   A row with an empty expiry (`thru_date` is NULL) is a permanent key. Find out who holds it before you delete it. **NOT PROVEN** whether any exist or are in use.
2. **Delete the keys** (everyone then has to log in again, the integration program does it automatically):
   ```sql
   DELETE FROM user_login_key;
   ```
3. **Restart Moqui** (`<moqui-container>`) to end all open sessions. **NOT PROVEN:** a restart is enough; Moqui also keeps session files in `runtime/sessions` inside the container. If users stay logged in after the restart, clear that directory too and restart again.

### 6.3 Proof

- [ ] The query in step 1 now returns no rows.
- [ ] Your earlier browser session asks you to log in again.

---

## 7. Part 4: The encryption key (`crypt-pass` and `crypt-salt`)

### 7.1 What it is and why it is different

Moqui keeps a few secrets that it needs to read back in plain form later (it cannot use a hash for these, because it must send them to someone else). They are stored **locked** with the system key. Examples of what is locked:

| Where | What | Likely in use? |
|---|---|---|
| `email_server.mail_password` | The password for sending e-mail | probably yes |
| `system_message_remote` (password, private key, shared secrets) | Credentials for other systems that Moqui talks to | possibly |
| `user_authc_factor.factor_option` | Second-factor (two-step login) secrets of users | only if users enrolled |
| `user_login_history.password_used` | Mistyped passwords at failed logins | possibly old rows |
| `entity_sync.target_password` and some "instance" tables | Rarely used | probably empty |

The key itself is made from four settings: `crypt-pass` (the password part), `crypt-salt` (the 8-character salt part), `crypt-iter` (how many times it is scrambled) and `crypt-algo` (the method). **Changing any of the four changes the key.**

**The key is never stored anywhere.** So you cannot "just change it". If you do, Moqui cannot open the old locked values any more. It shows the placeholder `_DECRYPT_FAILED_` instead. Nothing is destroyed; going back to the old key makes them readable again. But the data stays unreadable until you either put the old key back or re-enter the secrets.

**What is wrong today:**

- The real key in production is a **fixed text written in `MoquiProdConf.xml`** (and the old default in the compose file). It is a public default string from the repository. The `entity_ds_crypt_pass` value in `.env` and in the vault is **ignored**.
- It uses an old, weak method (`PBEWithMD5AndDES`) and only 10 rounds.
- A leaked key *plus* a leaked database copy would reveal the locked secrets. Because the key is public, the "lock" currently only protects against someone who has the database but not the repository.

### 7.2 Do you have to do this? (decision)

This is the "if necessary" part. Go through the questions in order.

1. **Count what is locked in production** (counts only; the output is safe to share):
   ```bash
   docker exec -i <postgres-container> psql -U postgres -d moqui_app_db <<'SQL'
   SELECT 'email_server.mail_password' AS field, count(*) AS filled FROM email_server WHERE mail_password IS NOT NULL AND mail_password <> ''
   UNION ALL SELECT 'system_message_remote.password',           count(*) FROM system_message_remote WHERE password IS NOT NULL AND password <> ''
   UNION ALL SELECT 'system_message_remote.private_key',        count(*) FROM system_message_remote WHERE private_key IS NOT NULL AND private_key <> ''
   UNION ALL SELECT 'system_message_remote.shared_secret',      count(*) FROM system_message_remote WHERE shared_secret IS NOT NULL AND shared_secret <> ''
   UNION ALL SELECT 'system_message_remote.send_shared_secret', count(*) FROM system_message_remote WHERE send_shared_secret IS NOT NULL AND send_shared_secret <> ''
   UNION ALL SELECT 'user_authc_factor.factor_option',          count(*) FROM user_authc_factor WHERE factor_option IS NOT NULL AND factor_option <> ''
   UNION ALL SELECT 'user_login_history.password_used',         count(*) FROM user_login_history WHERE password_used IS NOT NULL AND password_used <> ''
   UNION ALL SELECT 'entity_sync.target_password',              count(*) FROM entity_sync WHERE target_password IS NOT NULL AND target_password <> ''
   ORDER BY 1;
   SQL
   ```
   If one table does not exist, the whole command fails with `relation "..." does not exist`: delete that line and run it again. Write the result into the change record. (Result for production: **not recorded yet**.)

2. **Read the result:**

   | Result | What it means | What to do |
   |---|---|---|
   | All numbers are **0** (except maybe `user_login_history`) | Nothing important is locked. | **Easy path (7.3, Path 1).** The key can be changed with no loss. |
   | A **few** values in `email_server` / `system_message_remote` / `user_authc_factor` | Some secrets exist, and you or their owners can enter them again. | **Path 1 (7.3)**, then re-enter the secrets (7.5). |
   | **Many** values, or values that cannot easily be entered again (for example many users with two-step login) | Re-entering would be painful. | **Path 2 (7.4)**. Needs a change to the Moqui code (this repository is our own copy, so it is possible) and a rebuilt image. Ask the developer first. |

3. **Is it worth doing at all?** If the encrypted data is empty or unimportant and the database is never copied outside your control, the risk of leaving the key alone is low. But a public default key is still a finding. Most simply, do Path 1 once, because it costs little when nothing is locked.

### 7.3 Path 1: switch the key, then re-enter the secrets

This is the normal path. Do it in a separate window from Parts 1 to 3.

1. **Back up** (section 3), and keep the old `MoquiProdConf.xml`.
2. **Generate the new key parts** (section 2, rules 2 and 4): a new `crypt-pass` (40 chars) and a new `crypt-salt` (8 chars). Store them in the vault as `vault_env_entity_ds_crypt_pass` (exists) and a new `vault_env_entity_ds_crypt_salt`.
3. **Edit `customer/configs/moqui/conf/MoquiProdConf.xml`** (in the stack repository, not on the server by hand). In the `entity-facade` element:
   - **Remove** the fixed `crypt-pass="..."` and the fixed `crypt-algo="PBEWithMD5AndDES"`. Without them Moqui falls back to its stronger default method (`PBEWithHmacSHA256AndAES_128`).
   - **Add** `crypt-salt="${entity_ds_crypt_salt}"` and `crypt-pass="${entity_ds_crypt_pass}"`, which means "take the values from the environment".
   - Optionally add `crypt-iter="10000"` (more rounds is stronger; the old value is 10).
   - **NOT PROVEN:** that Moqui expands `${...}` in the `crypt-salt` attribute of this file. The same file style is used for `crypt-pass` in the Moqui example `MoquiProductionConf.xml`, so it is very likely, but the first start on the test server must show it. If the salt were not expanded, put the 8-character salt directly in this file instead (it is only half of the key).
4. **Edit `customer/docker-compose.yml`:** replace the hardcoded `entity_ds_crypt_pass=...` line with
   `entity_ds_crypt_pass=${entity_ds_crypt_pass}` and add `entity_ds_crypt_salt=${entity_ds_crypt_salt}`.
5. **Add `entity_ds_crypt_salt`** to the Ansible variables (`host_env_vars` in `inventory/group_vars/ips_mis/const.yml`, value from the vault), the same way `entity_ds_crypt_pass` is set.
6. **Deliver:** copy the configs to the host (`25-copy-configs.sh` / the Ansible config copy), write the `.env` (`05-write-env.sh`), recreate Moqui.
7. **Expect the old values to be unreadable now.** Look in the Moqui log: `Error decrypting field` lines are normal for the old locked values and **not** a sign of a failure. The system itself starts normally.
8. **Clear the unwanted old values**, then re-enter the wanted ones (7.5):
   ```sql
   UPDATE user_login_history SET password_used = NULL WHERE password_used IS NOT NULL;
   ```

### 7.4 Path 2: keep the old locked values readable (only for large amounts)

1. Ask the developer to add an **alternative decryption key** for the *old* key (`decrypt-alt`) to `framework/src/main/resources/MoquiDefaultConf.xml` in this repository: `crypt-salt="20201202"`, the current method, the old `crypt-pass`, and `crypt-iter="10"`. The old key comes from the config file as it is today (`MoquiProdConf.xml`).
2. Build and publish a new Moqui image.
3. Do steps 1 to 6 of Path 1 with this new image.
4. Moqui can now read old values with the alternative key, but it only **re-locks them with the new key when a record is saved with that field changed**. Reading never converts anything. So each record has to be re-saved (for example by re-entering the e-mail password in the screen, or a one-time script that clears and sets the field). **NOT PROVEN** how to force the save for each entity, which the developer must test first.
5. When no value needs the alternative key any more (check with a read test), remove the alternative.

### 7.5 Re-enter the locked secrets (both paths)

Changing the key is only half of the work. The values that were locked must be set again, and **this is also the moment to change them at the source**:

- **E-mail (SMTP) password:** set a new one at the mail provider, enter it in Moqui's e-mail server setting, send a test e-mail.
- **Remote systems** (`system_message_remote`): set new credentials at the other system, enter them in Moqui.
- **Two-step login:** affected users set up their second factor again.

**NOT PROVEN** which rows exist in production (see the count in 7.2). If the count was 0, this step is empty.

### 7.6 Proof

- [ ] After the restart and the re-entry, the Moqui log shows no new `Error decrypting field` lines.
- [ ] A test e-mail arrives.
- [ ] Every remote system that Moqui calls still answers.
- [ ] In the database, none of the fields from the count in 7.2 shows `_DECRYPT_FAILED_` in the application screen.
- [ ] The new `crypt-pass` and `crypt-salt` are in the vault **and** in the vault backup.

*(A very rare wrong key can open a value as garbage without an error, so do not trust "no error" alone; the test e-mail is the real proof.)*

### 7.7 Rollback of Part 4

Put the old `MoquiProdConf.xml` and the old `docker-compose.yml` back, recreate Moqui. Values locked with the old key become readable again. **Values you re-entered after the switch are locked with the new key and become unreadable again**, so note what was re-entered. **Keep the old key until a full backup cycle has passed after the switch.** Without the old key and the backup, data locked with it can never be recovered.

---

## 8. The whole job on one page (suggested order)

Test environment first, then production. One part at a time. Nothing in the middle of a part.

| Step | Part | Window | Done when |
|---|---|---|---|
| 1 | Prepare: checklist (section 3), backup, new values into the vault | before | Backup exists, vault updated |
| 2 | **Part 1** database passwords | window 1 | 4.5 proof ticked |
| 3 | **Part 2** user passwords and salts (5.2 to 5.5) | window 1 (same, or next day) | 5.5 proof ticked |
| 4 | **Part 3** login keys and sessions | window 1 | 6.3 proof ticked |
| 5 | Count the locked values (7.2) | any time, read only | Result in change record |
| 6 | **Part 4** encryption key + re-enter the locked secrets | window 2 (another day is fine) | 7.6 proof ticked |
| 7 | Write down what was done (no secret values), update this guide with anything that behaved differently | after | Change record saved |

---

## 9. What the Ansible project should do for us (shopping list, not built yet)

1. Vault names: `vault_env_entity_ds_password` and `vault_env_db_closure_user_pwd` (exist), `vault_env_entity_ds_crypt_pass` (exists), **new** `vault_env_entity_ds_crypt_salt`, and one password per Moqui account that a program needs (the integration one exists).
2. Playbooks (each with `no_log: true` on every task that handles a secret, and a "check" mode that shows what it would do):
   - **rotate database passwords:** the steps of 4.4, reading the password from a temporary root-only file,
   - **revoke login keys and restart Moqui:** Part 3,
   - **switch the encryption key:** Part 4 with the count query as a required first step; Path 1 or 2 must be chosen by a person.
3. In the **stack repository:** the compose file reads `entity_ds_crypt_pass` and `entity_ds_crypt_salt` from `.env`; `MoquiProdConf.xml` contains no key.
4. In **this repository (optional):** the alternative decryption key for Path 2, and a small script that re-saves locked fields.
5. **Provisioning:** a new environment must not keep the starting passwords; run Part 2 as the first step after install.

---

## 10. Things we have not proven (read this list before production)

1. Real values on the production server: container names, the real role names, whether the closure role still has the install script's fixed password (4.2 tells you).
2. Whether other programs than the integration container log in to Moqui or to the closure database with stored passwords.
3. The exact menu path in Moqui to change a user's password, and whether changing it by script (`/rest`, `/rpc`) is allowed on production.
4. Whether a restart ends all sessions or `runtime/sessions` must be cleared too.
5. Whether `crypt-salt="${entity_ds_crypt_salt}"` is expanded in `MoquiProdConf.xml` (very likely, test it).
6. The number of locked values in the production database (the 7.2 count was never run on production).
7. Whether permanent login keys exist.
8. Path 2 only: how to force the re-save of a locked field per record.

**Rule:** every item above is checked on the **test environment** first, and this guide is corrected with what is learned, before it is used on production.

## 11. Questions for the owner

- Which `.env` is used when production images and the generated database scripts are built? (See "the coupling" section in the Ansible document; it decides whether a rotated secret reaches the images.)
- Which Moqui accounts must exist in production and who owns each one? Should the integration program get its own dedicated account with only the permissions its routes need?
- Is there a second administrator who can recover the first one?

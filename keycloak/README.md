# Realm de Keycloak para `mto-stock`

Estos ficheros son la definición de lo que `mto-stock` necesita en el servidor de identidad. Se
versionan para que la configuración de Keycloak se revise en pull request como cualquier otro
cambio, y para que los entornos no diverjan por lo que alguien pinchó un día en la consola.

No contienen ningún secreto: `mto-stock` no emite tokens ni tiene cuenta de servicio, así que aquí
no hay ninguna credencial que guardar.

## El realm es compartido

`mto-stock` y `mto-configuration` viven en el **mismo realm** (`mto`). Se separa por entorno y no
por aplicación —`mto-dev`, `mto-pre`, `mto-pro`— porque los usuarios son los mismos para las dos
aplicaciones y un realm compartido evita duplicar identidades. El aislamiento entre servicios lo dan
los roles de cliente, no los realms.

De ahí que haya dos ficheros y no uno:

| Fichero | Cuándo |
|---|---|
| `mto-stock-partial-import.json` | El realm ya existe porque `mto-configuration` lo creó. Añade **solo** lo que `mto-stock` posee. |
| `mto-realm-local.json` | Realm completo y autónomo para el stack de `docker compose` de este repositorio, donde `mto-configuration` no está. Trae usuarios de desarrollo. |

El realm de referencia de la otra aplicación está en `mto-configuration/keycloak/mto-realm.json`.
Si se cambia el nombre del realm, es el campo `realm` de la primera línea del JSON.

El realm `master` se reserva para administrar Keycloak. Alojar ahí la aplicación pondría a
cualquiera de sus usuarios a un rol de distancia de administrar todo el servidor de identidad.

## Qué hay dentro

### Cliente

| Cliente | Tipo | Para qué |
|---|---|---|
| `mto-stock-api` | Confidencial, sin flujos | No inicia ninguna autenticación. Existe para declarar los permisos como roles de cliente y para ser la **audiencia** de los tokens. |

`mto-stock` no declara ningún cliente de navegador propio: usa el `mto-frontend` que ya define
`mto-configuration`. Tampoco declara cuenta de servicio, porque no hace llamadas salientes a otros
servicios. (`mto-realm-local.json` sí trae un `mto-frontend` mínimo, porque en el stack local no hay
nadie más que lo defina.)

### Permisos y perfiles

Los **permisos** son roles de cliente de `mto-stock-api` y son lo que comprueba el código. Los
**perfiles** son roles de realm compuestos que los agrupan, y son lo que se asigna a las personas.
Keycloak expande los compuestos al emitir el token, así que un usuario con un perfil llega con sus
permisos dentro de `resource_access`.

La ventaja de separarlos: cambiar lo que puede hacer un perfil se hace aquí, sin desplegar.

| Permiso | Concede |
|---|---|
| `stock-read` | Catálogo, stock derivado, histórico de movimientos y reservas |
| `stock-write` | Alta y modificación del catálogo; entradas, salidas, transferencias y reservas |
| `stock-delete` | Cancelación de reservas |
| `stock-adjust` | Ajustes de inventario — **además de** `stock-write` |
| `ops-metrics` | Lectura de los endpoints de Actuator |
| `ops-write` | Operaciones de Actuator que modifican estado |

| Perfil | Agrupa |
|---|---|
| `mto-warehouse-viewer` | `stock-read` |
| `mto-warehouse-operator` | `stock-read`, `stock-write` |
| `mto-warehouse-admin` | los de operario + `stock-delete`, `stock-adjust` |

`stock-adjust` va aparte porque un ajuste es la única escritura que corrige el saldo sin documento
con el que contrastarlo después: es lo que se usa tras un recuento, y también lo que cuadraría un
descuadre provocado a mano. Con un solo permiso, cualquiera que pudiera registrar una salida podría
además hacerla desaparecer del saldo. Por eso el endpoint pide los dos.

Los perfiles del almacén son propios y no extienden los de `mto-configuration` (`mto-editor`,
`mto-admin`): configurar catenaria y mover material son trabajos distintos, y quien define perfiles
de infraestructura no tiene por qué firmar movimientos de stock. Una misma persona puede llevar los
dos perfiles.

Los roles de realm **nunca** deben llamarse igual que un permiso. El converter emite los de realm
solo como `ROLE_REALM_*` precisamente para que no se confundan —y `KeycloakAuthorizationIT` lo
comprueba contra un servidor real—, pero conviene no tentar a la suerte.

## Cómo cargarlo

### Automático: el stack local de este repositorio

No hay que hacer nada. El servicio `keycloak` de `docker-compose.yml` monta
`mto-realm-local.json` en `/opt/keycloak/data/import` y arranca con `--import-realm`:

```bash
cp .env.example .env    # define al menos KC_BOOTSTRAP_ADMIN_PASSWORD
docker compose up --build
```

Keycloak queda en `http://localhost:8082` (consola: `admin` / lo que pusieras en
`KC_BOOTSTRAP_ADMIN_PASSWORD`) con el realm `mto` importado y tres usuarios de desarrollo, todos con
contraseña `local`:

| Usuario | Perfil |
|---|---|
| `almacen.lector` | `mto-warehouse-viewer` |
| `almacen.operario` | `mto-warehouse-operator` |
| `almacen.responsable` | `mto-warehouse-admin` |

Esos usuarios existen **solo** en `mto-realm-local.json` y no deben acercarse a un entorno
desplegado; `mto-stock-partial-import.json` no trae ninguno a propósito.

Si ya tienes en marcha el Keycloak de `mto-configuration`, comenta el servicio `keycloak` de
`docker-compose.yml` en lugar de levantar un segundo servidor: usan el mismo emisor
(`http://auth.mto.local:8082/realms/mto`), de modo que los tokens sirven para las dos aplicaciones.

### Automático: un Keycloak nuevo, fuera de compose

```bash
kc.sh start --import-realm     # con el fichero en /opt/keycloak/data/import/
```

### Sobre un realm que ya existe

Es el caso normal en un entorno donde `mto-configuration` ya desplegó el realm. Desde la consola:
**Realm settings → Partial import**, subiendo `mto-stock-partial-import.json` y con la estrategia de
conflicto en **Skip**, para añadir solo las piezas que falten sin pisar lo que ya hay.

O por la API de administración:

```bash
TOKEN=$(curl -s -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  -d username=admin -d password="$KC_ADMIN_PASSWORD" | jq -r .access_token)

curl -X POST "$KC_URL/admin/realms/mto/partialImport" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @keycloak/mto-stock-partial-import.json
```

El fichero no lleva `"ifResourceExists"`, así que Keycloak aplica su estrategia por defecto (`FAIL`)
y aborta si algo ya existe. Para reejecutarlo sobre un realm que ya tiene parte de esto, añade
`"ifResourceExists": "SKIP"` al objeto raíz del JSON, o usa la consola, que lo pregunta.

## Después de importar

Cuatro cosas que el fichero no puede traer, y dos de ellas son cruzadas con el otro repositorio.

1. **Añadir el *audience mapper* de `mto-stock-api` al cliente `mto-frontend`.** Es un cambio en un
   cliente que define `mto-configuration`, así que va en un pull request contra
   `mto-configuration/keycloak/mto-realm.json`, junto al mapper que ese cliente ya tiene para
   `mto-configuration-api`:

   ```json
   {
     "name": "audiencia-mto-stock-api",
     "protocol": "openid-connect",
     "protocolMapper": "oidc-audience-mapper",
     "config": {
       "included.client.audience": "mto-stock-api",
       "access.token.claim": "true",
       "id.token.claim": "false"
     }
   }
   ```

   En un servidor ya en marcha: Clients → `mto-frontend` → Client scopes → `mto-frontend-dedicated`
   → Add mapper → By configuration → Audience.

2. **Extender el perfil `mto-ops` con los roles de Actuator de `mto-stock`.** Ese perfil lo define
   `mto-configuration` y agrupa sus `ops-metrics` y `ops-write`; quien explota la plataforma explota
   las dos aplicaciones, así que el mismo perfil debe llevar también los de `mto-stock-api`. Va en
   el mismo pull request que el punto anterior, añadiendo a sus `composites`:

   ```json
   "mto-stock-api": ["stock-read", "ops-metrics", "ops-write"]
   ```

   `mto-stock-partial-import.json` no lo toca a propósito: una importación parcial que incluyera
   `mto-ops` reescribiría el perfil entero y se llevaría por delante los permisos de
   `mto-configuration`. Los perfiles de almacén (`mto-warehouse-*`) sí van ahí porque son nuevos y
   nadie más los define. En `mto-realm-local.json` el `mto-ops` ya viene resuelto, porque en el
   stack local no hay otra aplicación con la que compartirlo.

3. **Decidir qué puede hacer `mto-configuration` en el almacén.** Su cuenta de servicio
   `mto-configuration-svc` ya lleva un *audience mapper* que nombra a `mto-stock-api`, de modo que
   puede pedir tokens dirigidos a esta API. Pero llega sin permisos: hay que asignarle a mano los
   roles de cliente que necesite (Clients → `mto-configuration-svc` → Service accounts roles). No se
   conceden aquí a propósito — qué puede tocar un servicio en el almacén de otro es una decisión, no
   un valor por defecto.

4. **Crear los usuarios y asignarles su perfil.** El fichero de importación parcial no trae ninguno.

## El error más fácil de cometer

Si falta el *audience mapper*, la API rechaza **todos** los tokens con un 401 sin más explicación.
Keycloak añade la audiencia por su cuenta solo cuando el usuario tiene roles en ese cliente —lo hace
el mapper *audience resolve* del scope `roles`, que va de serie—, de modo que el fallo aparece justo
con quien no los tiene (una cuenta de servicio, un usuario recién creado) y parece intermitente. El
mapper explícito es lo que la garantiza siempre.

## Comprobar que quedó bien

`KeycloakAuthorizationIT` levanta un Keycloak real con un realm de la misma forma que estos
(`src/test/resources/keycloak/mto-stock-test-realm.json`, con usuarios de prueba añadidos) y
verifica contra él los permisos por verbo, la expansión de los compuestos, las dos mitades del
ajuste, que un rol de realm homónimo no concede el permiso, y la audiencia:

```bash
./mvnw verify -Dit.test=KeycloakAuthorizationIT -Dtest=NONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

Necesita Docker. Sin Docker el test se salta en lugar de fallar, y `./mvnw verify` lo ejecuta junto
al resto.

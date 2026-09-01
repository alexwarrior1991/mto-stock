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

Dos cosas que el fichero no puede traer. Las otras dos, que eran cruzadas con el otro repositorio,
**ya están hechas allí** y quedan descritas más abajo para saber qué se aplica y cuándo.

1. **Decidir qué puede hacer `mto-configuration` en el almacén.** Su cuenta de servicio
   `mto-configuration-svc` ya lleva un *audience mapper* que nombra a `mto-stock-api`, de modo que
   puede pedir tokens dirigidos a esta API. Pero llega sin permisos: hay que asignarle a mano los
   roles de cliente que necesite (Clients → `mto-configuration-svc` → Service accounts roles). No se
   conceden aquí a propósito — qué puede tocar un servicio en el almacén de otro es una decisión, no
   un valor por defecto.

2. **Crear los usuarios y asignarles su perfil.** El fichero de importación parcial no trae ninguno.

## Lo que aporta `mto-configuration`

Los dos objetos que cruzan las dos aplicaciones —el cliente `mto-frontend` y el perfil `mto-ops`—
los define el otro repositorio, y allí ya está hecho lo que `mto-stock` necesita de ellos.

### La audiencia del frontal

`mto-configuration/keycloak/mto-realm.json` da a `mto-frontend` un *audience mapper* hacia
`mto-stock-api`, junto al que ya tenía para su propia API. Sin él, un token del navegador puede
llegar aquí sin `mto-stock-api` en `aud`.

Conviene saber que **no es lo único que pone la audiencia**: el mapper *audience resolve* del scope
`roles`, que Keycloak asigna de serie, ya añade a `aud` todo cliente en el que el usuario tenga
algún rol. Quien tiene permisos de almacén entra por esa vía aunque falte el mapper explícito. Lo
que aporta el explícito es la garantía —el resolutor se cae si se acota el scope del token o el
*full scope* del cliente— y que la falta de permisos se vea como un **403** y no como un **401
"invalid token"**, que manda a depurar el emisor cuando el problema era otro.

### El perfil de explotación

`mto-ops` agrupa `ops-metrics` y `ops-write`, pero los permisos son roles de **cliente** y cada
aplicación solo lee los del suyo: `ops-metrics` existe dos veces, una en `mto-configuration-api` y
otra en `mto-stock-api`. Un `mto-ops` que solo lleve los de la otra aplicación recibe un 403 en el
Actuator de esta.

Lo que lo cubre es `mto-configuration/keycloak/mto-ops-cross-service.json`, una importación parcial
que redefine `mto-ops` con los roles de las dos APIs. Se aplica **después** de
`mto-stock-partial-import.json`, cuando el cliente `mto-stock-api` ya existe en el realm.

> **Un compuesto solo puede nombrar roles de clientes que existan en el realm que se está
> importando.** Keycloak aborta la importación entera con *App doesn't exist in role definitions*.
> Por eso el `mto-ops` que cubre las dos aplicaciones no puede vivir dentro de `mto-realm.json` —el
> fichero que **crea** el realm, cuando `mto-stock-api` todavía no está— y va en un fichero aparte.
> Es la misma razón por la que `mto-stock-partial-import.json` lleva dentro el cliente
> `mto-stock-api` además de los perfiles que lo nombran, y por la que en `mto-realm-local.json` el
> `mto-ops` viene resuelto con solo los roles de esta aplicación: en el stack local no hay otra.

Los *audience mapper* no tienen esa restricción: su destino se resuelve al emitir el token, no al
importar, así que sí pueden nombrar un cliente que aún no existe.

Y `mto-ops` sigue sin ir en `mto-stock-partial-import.json` a propósito: una importación parcial
reescribe el rol entero, de modo que incluirlo aquí se llevaría por delante los permisos de
`mto-configuration`. Los perfiles de almacén (`mto-warehouse-*`) sí van ahí porque son nuevos y
nadie más los define.

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

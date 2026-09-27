# Habi

## 1. Las decisiones clave y por qué las tomé

**Usé Java con Spring Boot y PostgreSQL.** Lo elegí porque es el lenguaje y framework que mas conozco, y escogi PostgreSQL ya que es una base de datos relacional y asimismo cumple con ACID que  ayuda a que las operaciones se guarden de forma segura.

**Los montos se guardan en centavos, como números enteros.** Decidi no utilizar double ya que algunas veces al dividir montos o sumar montos con double se pueden llegar a perder centavos del dinero de la transacción y la idea es que no se pierda nada. 

**El saldo solo cambia con movimientos que suman cero.** Para cada transacción decidi que se realizaran dos movimientos uno con saldo negativo que es a la cuenta que se le va aa restar y una con saldo negativo que es la que va a recibir el dinero. Al sumar estos dos movimientos el saldo total debe dar 0 mostrando que la plata no aparece ni desaparece solo cambia de cuenta. Estos moviminetos no se pueden borrar, se pueden corregir con otro movimiento.

**Bloqueo las cuentas antes de revisar el saldo, y siempre en el mismo orden.**
Decidi que se debrian bloquear, ya que si reviso el saldo sin bloquear, dos transferencias al mismo tiempo podrían ver
el mismo saldo y gastarlo dos veces. 

**Cada operación que mueve plata tiene una llave unica.** Si falla y la app reintenta con la misma llave, devuelvo el resultado original sin mover la plata otra vez, aya que es una llave unica.


**Para el feature elegí cobros divididos.** Elegi el feature de la cena ya que es con el que estoy mas relacionada y asimismo creo que es el más común y es una situación que puede estar en varios escenarios. 


## 2. Cómo sé que mi sistema no pierde un peso

### Qué puede salir mal
1. Gastar la misma plata dos veces.
2. Cobrar dos veces por un reintento.
3. Que la plata aparezca o desaparezca por un error.
4. Errores de redondeo.
5. Que dos operaciones se queden esperándose para siempre.
6. Que alguien modifique el historial.
7. En los cobros: que una cuota se pague dos veces

### Cómo lo protejo
1. Bloqueo la cuenta antes de revisar el saldo de la segunda transacción, de este modo la segunda transaccción espera a que termine al primera y cuando revisa ya ve el saldo nuevo.
2. Se crea una llave de idempotencia para que cada transacción tenga una llave unica, si esta llega repetida se devuelve el resultado original sin mover ningun dinero. 
3. Todo lo que se realiza en una transacción debe dar 0 en total para que lo que sea restado de una cuenta sea la misma ccantidad sumada en otra.
4. Los montos que tengan centavos se toman como enteros para que las sumas queden exactas y nos e pierda dinero.
5. Se bloquean las cuentas en el mismo orden para que mientras una espera la otra avanza.
6. No se pueden borar movimientos en la base de datos.
7. En los cobros una cuota se paga una sola vez y la plata se mueve al mismo tiempo que se marca como pagada.

### Qué evidencia tengo de que funciona

Realice tests con respecto a la base de datos, y
después de cada uno se reviso que la suma de todos los saldos dé 0 y que nadie quede en negativo. Algunos tests lanzan muchas operaciones al mismo tiempo para ver si falla con esto. Asimismo se probo por medio de la terminal para ver como se ejecutaba. 

## 3. Qué dejé fuera y por qué

1. No se tomo en cuenta la autenticación y autorización. Actualmente cualquiera puede transferir desde cualquier cuenta. Estoq uedo afuera ya que por ahora me centre en que no se perdiera plata en las diferentes transacciones.
2. No se realizó un frontend para poder interactuar con la aplicación. 
3. Las cuotas se realizan completas y no parciales
4. Un reintento al crear una cuenta puede llevar a que se creen dos cuentas vacias, no tienen una llave unica.

## 4. Qué haría distinto con más tiempo
1. Implementar la autenticación, para que solo quien sea dueño de la cuenta pueda realizar transacciones frente a esta.
2. Implementar los pagos parciales 
3. Pruebas de carga para ver cuantos usuarios puede tener la aplicación
4. Realizar un frontend para que se facilite la interacción con la aplicación.


## 5. Qué NO sé
1. No se como funcionaria al conectarse con un banco o pasarela de pagfos ya que actualmente esto es simulado y no se tomo en cuenta.
2. No se si la aplicación podria soportar una gran cantidad de susuarios o si la conexión con la base fallaria.
3. No se si se necesitan diferentes bases de datos para manejar toda la plicación o solo con una es suficiente
4 No se si los test realizados cumplen todos los posibles casos o errores que puede presentar la aplicación.

## 6. Los supuestos que hice y por qué
- La carga dee saldo siempre debe funcionar, ya que es simulada.
- Las cuotas son iguales
- Tdo esta en una sola base de datos
- Los nombres no son unicos
- Un cobro tiene maximo 50 personas y solo aparecen una vez
- Las cuentas no se cierran

## 7. Cómo usé IA

### Qué herramientas
Utilice Claude Code con la extensión de vscode

### Con qué dinámica y en qué etapas
Al comienzo entendi el problema y cree el proyecto en ese momento, para realizar el sacfolding se le pidio que lo creara la ia, despues de esto para empezar a implementar le pedi que realizara la creación de la base de datos con las especificaciones y de este modo ya se empezo a crear la app y los test de esta y se implemento el acceso por medio de api. Despues ejecute manualmente los test con postman. Y al final elegi la idea de dividir el gasto para implemntarlo de la misma forma que el anterior. Antes de dad paso se pedia un plan corto a la IA  y s dependiendo de esto lo aprobaba o yo sugeria cambios fernete a la implementación y al final se ejecutaban los tests. Si no entendia algo de lo realizado pedia ejemplos antes de seguir con el siguiente paso.

### Qué le pedía y qué decidía yo
Yo definía la estructura del proyecto: el feature, la organización de las carpetas, los nombres, los tipos de funciones y probar con Postman. La IA escribía
el código y los tests a partir de eso, los corría y me explicaba cada decisión. Si algo no lo podía explicar, lo cambiábamos.

### Una vez en que la IA se equivocó, o casi me hizo equivocar, y cómo me di cuenta
La IA me decía que los tests pasaban, y en sus ejecuciones era cierto. Pero cuando yo corrí los test directamente, fallaron 77 de 84. La IA estaba pasando a mano en cada comando la ruta de Docker de Colima, y en mi computadora esa ruta apuntaba a Docker Desktop, que no estaba corriendo. Lo arreglamos apuntando esa ruta Colima que es la mnera en como ejecuto el docker en mi computador.

## 8. Qué aprendí

### Qué es nuevo para mí
La idempotencia, bloquear filas en la base de datos y por qué la plata no se guarda con decimales.

### Qué me sorprendió
Todo lo que puede fallar en algo que parece tan sencillo como mover plata entre dos cuentas: dos pagos que llegan al mismo tiempo, una operación que se repite por un reintento o un centavo que se pierde al dividir una cuenta.

### Qué me llevo
Entender el porqué de cada decisión y verificar las cosas yo misma en mi máquina.

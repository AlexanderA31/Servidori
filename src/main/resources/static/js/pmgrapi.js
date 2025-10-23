"use strict"

/**
 * Librería de cliente para interaccionar con el servidor de Pmgr.
 * Prácticas de IU 2020-21
 *
 * Para las prácticas, por favor - NO TOQUES ESTE CÓDIGO.
 *
 * Fuera de las prácticas, lee la licencia: dice lo que puedes hacer con él, que es esencialmente
 * lo que quieras siempre y cuando no digas que lo escribiste tú o me persigas por haberlo escrito mal.
 */

/**
 * El estado global de la aplicación.
 */
class GlobalState {
  constructor(token, jobs, printers, groups) {
    this.token = token;
    this.jobs = jobs || [];
    this.printers = printers || [];
    this.groups = groups || [];
  }
}

/**
 * Un trabajo de impresión
 */
class Job {
  constructor(id, printer, owner, fileName) {
    this.id = id;
    this.printer = printer;
    this.owner = owner;
    this.fileName = fileName;
  }
}

/**
 * Un grupo de impresoras
 */
class Group {
    constructor(id, name, printers) {
      this.id = id;
      this.name = name;
      this.printers = printers || [];
    }
}

/**
 * Posibles estados
 */
const PrinterStates = {
    PRINTING: 'printing',
    PAUSED: 'paused',
    NO_PAPER: 'no_paper',
    NO_INK: 'no_ink',
}

/**
 * Una impresora
 */
class Printer {
    constructor(id, alias, model, location, ip, groups, queue, status) {
      this.id = id;
      this.alias = alias;
      this.model = model;
      this.location = location;
      this.ip = ip;
      this.queue = queue || [];
      this.groups = groups || []
      Util.checkEnum(status, PrinterStates);
      this.status = status;
    }
}

/**
 * Utilidades
 */
const UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const LOWER = 'abcdefghijklmnopqrstuvwxyz';
const DIGITS = '01234567890';
class Util {

    /**
     * throws an error if value is not in an enum
     */
    static checkEnum(a, enumeration) {
        const valid = Object.values(enumeration);
        if (a === undefined) {
            return;
        }
        if (valid.indexOf(a) === -1) {
        throw Error(
            "Invalid enum value " + a +
            ", expected one of " + valid.join(", "));
        }
    }

    /**
    * Genera un entero aleatorio entre min y max, ambos inclusive
    * @param {Number} min 
    * @param {Number} max 
    */
    static randomInRange(min, max) {
        return Math.floor(Math.random() * (max - min + 1)) + min
    }

    static randomChar(alphabet) {
      return alphabet.charAt(Math.floor(Math.random() * alphabet.length));
    }
    
    static randomString(count, alphabet) {
      const n = count || 5;
      const valid = alphabet || UPPER + LOWER + DIGITS;
      return new Array(n).fill('').map(() => this.randomChar(valid)).join('');
    }

    static randomPass() {
      const n = 7;
      const prefix = this.randomChar(UPPER) + this.randomChar(LOWER) + this.randomChar(DIGITS);
      const valid = UPPER + LOWER + DIGITS;
      return prefix + new Array(n-3).fill('').map(() => this.randomChar(valid)).join('');
    }

    /**
     * Genera un identificador "unico" de 5 caracteres
     */
    static randomWord(count, capitalized) {
        return capitalized ? 
             this.randomChar(UPPER) + this.randomString(count -1, LOWER) :
             this.randomString(count, LOWER);
    }
    
    /**
     * Genera palabras al azar, de forma configurable
     */
    static randomText(wordCount, allCapitalized, delimiter) {        
        let words = [ this.randomWord(5, true)];
        for (let i=1; i<(wordCount || 1); i++) words.push(this.randomWord(5, allCapitalized));
        return words.join(delimiter || ' ');
    }

    /**
     * Devuelve algo al azar de un array
     */
    static randomChoice(array) {
        return array[Math.floor(Math.random() * array.length)];
    }

    /**
     * Genera una fecha al azar entre 2 dadas
     * https://stackoverflow.com/a/19691491
     */
    static randomDate(fechaIni, maxDias) {
        let dia = new Date(fechaIni);
        dia.setDate(dia.getDate() - Util.randomInRange(1, maxDias));
        return dia;
    }

    /**
     * Devuelve n elementos no-duplicados de un array
     * de https://stackoverflow.com/a/11935263/15472
     */
    static randomSample(array, size) {
        var shuffled = array.slice(0), i = array.length, temp, index;
        while (i--) {
            index = Math.floor((i + 1) * Math.random());
            temp = shuffled[index];
            shuffled[index] = shuffled[i];
            shuffled[i] = temp;
        }
        return shuffled.slice(0, size);
    }

    static randomModels = [
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
        Util.randomWord().toUpperCase() + "-" + Util.randomInRange(100,2000),
    ];

    static randomLocations = [
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500),
        "Despacho " + Util.randomInRange(100,500)
    ];

    /**
     * Genera una impresora al azar, sin trabajos
     */
    static randomPrinter(id) {
        return new Printer(
            id,
            Util.randomString(),
            Util.randomChoice(Util.randomModels),
            Util.randomChoice(Util.randomLocations),
            "192.168.0." + Util.randomInRange(10,250),
            [],
            [],
            Util.randomChoice([
                PrinterStates.PAUSED,
                PrinterStates.NO_PAPER,
                PrinterStates.NO_INK,
                PrinterStates.PRINTING
            ])
        );
    }

    /**
     * Genera un grupo de impresoras. Cada impresora pasada tiene un p%
     * de ser incluida
     */
    static randomGroup(id, printers, percentageProbabilityToInclude) {
        let g = new Group(
            id,
            Util.randomString()
        );
        for (let p of printers) {
            if (Util.randomInRange(0, 100) < percentageProbabilityToInclude) {
                g.printers.push(p.id);
            }
        }
        return g;
    }    

    /**
     * Llena un array con el resultado de llamar a una funcion
     */
    static fill(count, callback) {
        let f = callback;
        let results = [];
        for (let i=0; i<count; i++) results.push(f());
        return results;
    }
}


// cache de IDs; esto no se exporta
let cache = {};

// acceso y refresco de la cache de IDs; privado
function getId(id, object) {
    const found = cache[id] !== undefined;
    if (object) {
        if (found) throw Error("duplicate ID: " + id);
        cache[id] = object;
    } else {
        if (!found) throw Error("ID not found: " + id);
        return cache[id];
    }
}

// sube datos en json, espera json de vuelta; lanza error por fallos (status != 200)
function go(url, method, data = {}) {
  let params = {
    method: method, // POST, GET, POST, PUT, DELETE, etc.
    headers: {
      "Content-Type": "application/json; charset=utf-8",
    },
    body: JSON.stringify(data)
  };
  if (method === "GET") {
	  // GET requests cannot have body; I could URL-encode, but it would not be used here
	  delete params.body;
  }
  console.log("sending", url, params)
  return fetch(url, params).then(response => {
    if (response.ok) {
        return data = response.json();
    } else {
        response.text().then(t => {throw new Error(t + ", at " + url)});
    }
  }).catch((error) => {
    console.error("Error en llamada a", url, "con", params, error);
  })
}

// actualiza el estado de la aplicación con el resultado de una petición
function updateState(data) {
    if (data === undefined) {
        return; // excepto si la petición no devuelve nada
    }
    cache = {};
    globalState = new GlobalState(data.token, data.jobs, data.printers, data.groups);
    globalState.jobs.forEach(o => getId(o.id, o));
    globalState.printers.forEach(o => getId(o.id, o));
    globalState.groups.forEach(o => getId(o.id, o));
    console.log("Updated state", globalState);
    return data;
}

// funcion para generar datos de ejemplo: impresoras, grupos, trabajos, ...
// se puede no-usar, o modificar libremente
async function populate(minPrinters, maxPrinters, minGroups, maxGroups, jobCount) {
      const U = Util;

      // genera datos de ejemplo
      minPrinters = minPrinters || 10;
      maxPrinters = maxPrinters || 20;
      minGroups = minGroups || 1;
      maxGroups = maxGroups || 3;
      jobCount = jobCount || 100;
      let lastId = 0;

      let printers = U.fill(U.randomInRange(minPrinters, maxPrinters),
          () => U.randomPrinter(lastId ++));

      let groups = U.fill(U.randomInRange(minPrinters, maxPrinters),
          () => U.randomGroup(lastId ++, printers, 50));

      let jobs = [];
      for (let i=0; i<jobCount; i++) {
          let p = U.randomChoice(printers);
          let j = new Pmgr.Job(lastId++,
            p.id,
            [
                U.randomChoice([
                    "Alice", "Bob", "Carol", "Daryl", "Eduardo", "Facundo", "Gloria", "Humberto"]),
                U.randomChoice([
                    "Fernández", "García", "Pérez", "Giménez", "Hervás", "Haya", "McEnroe"]),
                U.randomChoice([
                    "López", "Gutiérrez", "Pérez", "del Oso", "Anzúa", "Báñez", "Harris"]),
            ].join(" "),
            U.randomString() + ".pdf");
          p.queue.push(j.id);
          jobs.push(j);
      }

      if (globalState.token) {
          console.log("Updating server with all-new data");
          const idMap = {}; // mapa de ids generadas a reales
          const filter = (forbidden, raw) => Object.keys(raw)
            .filter(key => !forbidden.includes(key))
            .reduce((obj, key) => {
                obj[key] = raw[key];
                return obj;
            }, {});

          // add all printers, without queues
          for (let p of printers) {
            await addPrinter(filter(["queue", "id"], p));
            idMap[p.id] = globalState.printers.filter(x => x.alias == p.alias)[0].id;
          }
          console.log(printers, globalState.printers, idMap)
          // add all groups, with printers
          for (let g of groups) {
            g.printers = g.printers.map(id => idMap[id]); // pero usa IDs asignados por servidor
            await addGroup(g);
          }
          // add all jobs, with printers
          for (let j of jobs) {
            j.printer = idMap[j.printer];    // pero usa IDs asignados por servidor
            await addJob(j);
          }
      } else {
          console.log("Local update - not connected to server");
          updateState({
            jobs: jobs,
            printers: printers,
            groups: groups
          });
      }
}

// el estado global
let globalState = new GlobalState();   

// la direccion del servidor
let serverApiUrl = "//localhost:8080/api/";

// el token actual (procedente del ultimo login)
let serverToken = "no-has-hecho-login";

// llama a esto con la URL de la api a la que te quieres conectar
function connect(apiUrl) {
    serverApiUrl = apiUrl;
    serverToken = "no-has-hecho-login";
}

// acceso externo a la cache
function resolve(id) {
    return cache[id];
}

// hace login. Todas las futuras operaciones usan el token devuelto
function login(username, password) {
    return go(serverApiUrl + "login", 'POST', {username: username, password: password})
        .then(d => { if (!d) return; serverToken = d.token; updateState(d); return d;});
}

// hace logout, destruyendo el token usado
function logout(id) {
    return go(serverApiUrl + serverToken + "/logout", 'POST');
}

// añade una nueva impresora; trabajos, si se especifican, deben existir
function addPrinter(printer) {
    return go(serverApiUrl + serverToken + "/addprinter", 'POST', printer)
        .then(d => updateState(d));
}

// añade un nuevo grupo; impresoras, si se especifican, deben existir
function addGroup(group) {
    return go(serverApiUrl + serverToken + "/addgroup", 'POST', group)
        .then(d => updateState(d));
}

// añade un nuevo usuario (SOLO ADMIN)
function addUser(username, password) {
    return go(serverApiUrl + serverToken + "/adduser", 'POST', { username, password })
        .then(d => updateState(d));
}

// añade un trabajo
function addJob(job) {
    return go(serverApiUrl + serverToken + "/addjob", 'POST', job)
        .then(d => updateState(d));
}

// elimina una impresora, por id
function rmPrinter(id) {
    return go(serverApiUrl + serverToken + "/rmprinter", 'POST', {id: id})
        .then(d => updateState(d));
}

// elimina un grupo, por id
function rmGroup(id) {
    return go(serverApiUrl + serverToken + "/rmgroup", 'POST', {id: id})
        .then(d => updateState(d));
}

// elimina un trabajo, por id
function rmJob(id) {
    return go(serverApiUrl + serverToken + "/rmjob", 'POST', {id: id})
        .then(d => updateState(d));
}

// elimina un usuario, por id (SOLO ADMIN)
function rmUser(id) {
    return go(serverApiUrl + serverToken + "/rmuser", 'POST', {id: id})
        .then(d => updateState(d));
}

// modifica un objeto. Cualquier referencia debe existir
function setPrinter(o) {
    return go(serverApiUrl + serverToken + "/setprinter/", 'POST', o)
        .then(d => updateState(d));
}

// modifica un objeto. Cualquier referencia debe existir
function setGroup(o) {
    return go(serverApiUrl + serverToken + "/setgroup/", 'POST', o)
        .then(d => updateState(d));
}

// modifica un objeto. Cualquier referencia debe existir
function setJob(o) {
    return go(serverApiUrl + serverToken + "/setjob/", 'POST', o)
        .then(d => updateState(d));
}

// modifica un objeto. Cualquier referencia debe existir (SOLO ADMIN)
function setUser(o) {
    return go(serverApiUrl + serverToken + "/setuser/", 'POST', o)
        .then(d => updateState(d));
}

// actualiza el estado de la aplicación
function list() {
    return go(serverApiUrl + serverToken + "/list", 'POST')
        .then(d => updateState(d));
}

// lista los usuarios activos (SOLO ADMIN)
function ulist() {
    return go(serverApiUrl + serverToken + "/ulist", 'POST')
        .then(d => updateState(d));
}

// cosas que estarán disponibles desde fuera de este módulo
export {

  // Clases
  GlobalState,   // estado global
  Group,         // grupo
  Printer,       // impresora
  PrinterStates, // posibles estados de impresión
  Job,           // trabajo de impresión

  // Estado local
  globalState,   // el estado de la aplicación, según la última respuesta
  resolve,       // consulta un id en la cache
  connect,       // establece URL del servidor. Debe llamarse antes de nada
  updateState,   // usa ésto para actualizar el globalState, para que la cache funcione

  // Métodos. Todos (menos login) usan el token que devuelve login
  login,         // (username, password) --> devuelve un token válido
  logout,        // ()                   --> invalida un token

  // operaciones sobre el modelo; importante: todas las referencias deben existir
  addPrinter, addGroup, addJob,
   rmPrinter,  rmGroup,  rmJob,
  setPrinter, setGroup, setJob,

  // Refresca el estado local, sin hacer cambios
  list,

  // Utilidades varias que no forman parte de la API
  Util, populate,

  // operaciones de administración (para configurar el servidor)
  addUser, rmUser, setUser, ulist
};

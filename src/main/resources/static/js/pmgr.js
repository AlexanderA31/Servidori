"use strict"

import * as Pmgr from './pmgrapi.js'

/**
 * Librería de cliente para interaccionar con el servidor de PrinterManager (prmgr).
 * Prácticas de IU 2020-21
 *
 * Para las prácticas de IU, pon aquí (o en otros js externos incluidos desde tus .htmls) el código
 * necesario para añadir comportamientos a tus páginas. Recomiendo separar el fichero en 2 partes:
 * - funciones que pueden generar cachos de contenido a partir del modelo, pero que no
 *   tienen referencias directas a la página
 * - un bloque rodeado de $(() => { y } donde está el código de pegamento que asocia comportamientos
 *   de la parte anterior con elementos de la página.
 *
 * Fuera de las prácticas, lee la licencia: dice lo que puedes hacer con él, que es esencialmente
 * lo que quieras siempre y cuando no digas que lo escribiste tú o me persigas por haberlo escrito mal.
 */

//
// PARTE 1:
// Código de comportamiento, que sólo se llama desde consola (para probarlo) o desde la parte 2,
// en respuesta a algún evento.
//

function createPrinterItem(printer) {
  const rid = 'x_' + Math.floor(Math.random()*1000000);
  const hid = 'h_'+rid;
  const cid = 'c_'+rid;

  // usar [] en las claves las evalua (ver https://stackoverflow.com/a/19837961/15472)
  const PS = Pmgr.PrinterStates;
  let pillClass = { [PS.PAUSED] : "badge-secondary",
                    [PS.PRINTING] : "badge-success",
                    [PS.NO_INK] : "badge-danger",
                    [PS.NO_PAPER] : "badge-danger" };

  let allJobs = printer.queue.map((id) =>
     `<span class="badge badge-secondary">${id}</span>`
  ).join(" ");

  return `
    <div class="card">
    <div class="card-header" id="${hid}">
        <h4 class="mb-0">
            ${printer.alias}
        </h4>
        <span class="badge badge-pill ${pillClass[printer.status.toLowerCase()]}">${printer.status}</span>
    </div>

    <div>
        <div class="card-body pcard">
            ${printer.model} at ${printer.location}
            <hr>
            ${allJobs}
        </div>
    </div>
    </div>
 `;
}

function createGroupItem(group) {
  let allPrinters = group.printers.map((id) =>
     `<span class="badge badge-secondary">${Pmgr.resolve(id).alias}</span>`
  ).join(" ");
 return `
    <div class="card">
    <div class="card-header">
        <h4 class="mb-0">
            <b class="pcard">${group.name}</b>
        </h4>
    </div>
    <div class="card-body pcard">
        ${allPrinters}
    </div>
    </div>
`;
}

function createJobItem(job) {
 return `
    <div class="card">
    <div class="card-header">
        <h4 class="mb-0">
            <b class="pcard">${job.fileName}</b>
        </h4>
    </div>
    <div class="card-body pcard">
        ${job.owner} @${Pmgr.resolve(job.printer).alias}
    </div>
    </div>
`;
}

/**
 * En un div que contenga un campo de texto de búsqueda
 * y un select, rellena el select con el resultado de la
 * funcion actualizaElementos (que debe generar options), y hace que
 * cualquier búsqueda filtre los options visibles.
 */
function activaBusquedaDropdown(div, actualizaElementos) {
      let search = $(div).find('input[type=search]');
      let select = $(div).find('select');
      console.log(search, select);

      // vacia el select, lo llena con impresoras validas
      select.empty();
      actualizaElementos(select);

      // filtrado dinámico
      $(search).off('input'); // elimina manejador anterior, si lo habia
      $(search).on('input', () => {
        let w = $(search).val().trim().toLowerCase();
        let items = $(select).find("option");

        items.each((i, o) =>
            $(o).text().toLowerCase().indexOf(w) > -1 ? $(o).show() : $(o).hide());

        // muestra un array JS con los seleccionados
        console.log("Seleccionados:", $(select).val());
      });
}

//
// Función que refresca toda la interfaz. Debería llamarse tras cada operación
// por ejemplo, Pmgr.addGroup({"name": "nuevoGrupo"}).then(update()); // <--
//
function update(result) {
    try {
      // vaciamos los contenedores
      $("#impresoras").empty();
      $("#grupos").empty();
      $("#trabajos").empty();
      // y los volvemos a rellenar con su nuevo contenido
      Pmgr.globalState.printers.forEach(m =>  $("#impresoras").append(createPrinterItem(m)));
      Pmgr.globalState.groups.forEach(m =>  $("#grupos").append(createGroupItem(m)));
      Pmgr.globalState.jobs.forEach(m =>  $("#trabajos").append(createJobItem(m)));
    } catch (e) {
      console.log('Error actualizando', e);
    }

    // para que siempre muestre los últimos elementos disponibles
    activaBusquedaDropdown($('#dropdownBuscableImpresora'),
          (select) => Pmgr.globalState.printers.forEach(m =>
                    select.append(`<option value="${m.id}">${m.alias}</option>`))
    );
}

//
// PARTE 2:
// Código de pegamento, ejecutado sólo una vez que la interfaz esté cargada.
// Generalmente de la forma $("selector").cosaQueSucede(...)
//
$(function() {

    // Servidor a utilizar. También puedes lanzar tú el tuyo en local (instrucciones en Github)
    const serverUrl = "http://localhost:8080/api/";
//    const serverUrl = "http://gin.fdi.ucm.es/iu/api/";
    Pmgr.connect(serverUrl);

  // ejemplo de login
    Pmgr.login("a", "aa").then(d => {
        if (d !== undefined) {
            //Pmgr.populate(); -- genera datos de prueba, usar sólo 1 vez
            update();
            console.log("login ok!");
        } else {
            console.log(`error en login (revisa la URL: ${serverUrl}, y verifica que está vivo)`);
            console.log("Generando datos de ejemplo para uso en local...")

            Pmgr.populate();
            update();
        }
    });
});

// cosas que exponemos para usarlas desde la consola
window.update = update;
window.Pmgr = Pmgr;
window.createPrinterItem = createPrinterItem



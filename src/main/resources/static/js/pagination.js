// Funciones de paginación del lado del cliente

function changePageSize(select, baseUrl) {
    const size = select.value;
    const url = new URL(window.location.href);
    url.searchParams.set('size', size);
    url.searchParams.set('page', '0'); // Resetear a primera página
    window.location.href = url.toString();
}

function goToPage(page, baseUrl) {
    const url = new URL(window.location.href);
    url.searchParams.set('page', page);
    window.location.href = url.toString();
}

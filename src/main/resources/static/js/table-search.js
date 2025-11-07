// Funcionalidad de búsqueda en tablas

/**
 * Filtra una tabla basándose en el texto de búsqueda
 * @param {string} searchInputId - ID del campo de búsqueda
 * @param {string} tableSelector - Selector CSS de la tabla
 */
function searchTable(searchInputId, tableSelector) {
    const searchInput = document.getElementById(searchInputId);
    const table = document.querySelector(tableSelector);
    
    if (!searchInput || !table) {
        console.warn('Search input or table not found');
        return;
    }
    
    const searchTerm = searchInput.value.toLowerCase().trim();
    const tbody = table.querySelector('tbody');
    
    if (!tbody) {
        console.warn('Table tbody not found');
        return;
    }
    
    const rows = tbody.querySelectorAll('tr:not(.dept-expanded-row):not(.empty-state)');
    let visibleCount = 0;
    
    rows.forEach(row => {
        const text = row.textContent.toLowerCase();
        const shouldShow = text.includes(searchTerm);
        
        row.style.display = shouldShow ? '' : 'none';
        
        if (shouldShow) {
            visibleCount++;
        }
        
        // Si es una fila de departamento con fila expandida, ocultar/mostrar también la expandida
        const nextRow = row.nextElementSibling;
        if (nextRow && nextRow.classList.contains('dept-expanded-row')) {
            nextRow.style.display = shouldShow ? '' : 'none';
        }
    });
    
    // Mostrar mensaje si no hay resultados
    updateNoResultsMessage(tbody, visibleCount, searchTerm);
}

/**
 * Actualiza el mensaje de "sin resultados"
 */
function updateNoResultsMessage(tbody, visibleCount, searchTerm) {
    // Eliminar mensaje previo si existe
    const existingMessage = tbody.querySelector('.no-results-row');
    if (existingMessage) {
        existingMessage.remove();
    }
    
    // Si no hay resultados y hay término de búsqueda, mostrar mensaje
    if (visibleCount === 0 && searchTerm) {
        const table = tbody.closest('table');
        const columnCount = table.querySelectorAll('thead th').length;
        
        const noResultsRow = document.createElement('tr');
        noResultsRow.className = 'no-results-row';
        noResultsRow.innerHTML = `
            <td colspan="${columnCount}" style="text-align: center; padding: 2rem; color: #999;">
                <i class="fas fa-search" style="font-size: 2rem; margin-bottom: 0.5rem; display: block;"></i>
                <p style="margin: 0;">No se encontraron resultados para "<strong>${escapeHtml(searchTerm)}</strong>"</p>
            </td>
        `;
        tbody.appendChild(noResultsRow);
    }
}

/**
 * Escapa HTML para prevenir XSS
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Limpia la búsqueda
 */
function clearSearch(searchInputId, tableSelector) {
    const searchInput = document.getElementById(searchInputId);
    if (searchInput) {
        searchInput.value = '';
        searchTable(searchInputId, tableSelector);
        searchInput.focus();
    }
}

/**
 * Inicializa el buscador con detección de Enter
 */
function initializeSearch(searchInputId, tableSelector) {
    const searchInput = document.getElementById(searchInputId);
    if (searchInput) {
        // Búsqueda en tiempo real
        searchInput.addEventListener('input', () => {
            searchTable(searchInputId, tableSelector);
        });
        
        // Búsqueda al presionar Enter
        searchInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                searchTable(searchInputId, tableSelector);
            }
        });
    }
}

// Auto-inicialización al cargar la página
document.addEventListener('DOMContentLoaded', () => {
    // Buscar todos los campos de búsqueda y auto-inicializar
    document.querySelectorAll('[data-search-table]').forEach(input => {
        const tableSelector = input.getAttribute('data-search-table');
        initializeSearch(input.id, tableSelector);
    });
});

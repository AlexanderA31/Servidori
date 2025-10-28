/**
 * Sistema de Notificaciones y Modales de Confirmación
 */

// Crear contenedor de notificaciones si no existe
function ensureNotificationContainer() {
    let container = document.getElementById('notification-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'notification-container';
        container.className = 'notification-container';
        document.body.appendChild(container);
    }
    return container;
}

/**
 * Mostrar notificación
 * @param {string} message - Mensaje a mostrar
 * @param {string} type - Tipo: 'success', 'error', 'warning', 'info'
 * @param {number} duration - Duración en ms (default: 5000)
 * @param {string} title - Título opcional
 */
window.showNotification = function(message, type = 'info', duration = 5000, title = null) {
    const container = ensureNotificationContainer();
    
    // Crear notificación
    const notification = document.createElement('div');
    notification.className = `notification ${type}`;
    
    // Iconos según tipo
    const icons = {
        success: 'fas fa-check-circle',
        error: 'fas fa-times-circle',
        warning: 'fas fa-exclamation-triangle',
        info: 'fas fa-info-circle'
    };
    
    // Títulos por defecto
    const titles = {
        success: 'Éxito',
        error: 'Error',
        warning: 'Advertencia',
        info: 'Información'
    };
    
    const finalTitle = title || titles[type];
    
    notification.innerHTML = `
        <div class="notification-icon">
            <i class="${icons[type]}"></i>
        </div>
        <div class="notification-content">
            <div class="notification-title">${finalTitle}</div>
            <div class="notification-message">${message}</div>
        </div>
        <button class="notification-close" onclick="this.parentElement.remove()">
            <i class="fas fa-times"></i>
        </button>
    `;
    
    // Agregar al contenedor
    container.appendChild(notification);
    
    // Auto-cerrar después de la duración
    if (duration > 0) {
        setTimeout(() => {
            notification.classList.add('notification-exit');
            setTimeout(() => notification.remove(), 300);
        }, duration);
    }
    
    return notification;
};

/**
 * Modal de confirmación moderno
 * @param {Object} options - Opciones del modal
 * @returns {Promise<boolean>} - Promesa que resuelve true si confirma, false si cancela
 */
window.showConfirm = function(options = {}) {
    return new Promise((resolve) => {
        const {
            title = '¿Estás seguro?',
            message = '¿Deseas continuar con esta acción?',
            confirmText = 'Confirmar',
            cancelText = 'Cancelar',
            type = 'warning', // 'warning', 'danger', 'info'
            confirmClass = 'btn-danger',
            showCheckbox = false,
            checkboxText = 'No volver a preguntar'
        } = options;
        
        // Iconos según tipo
        const icons = {
            warning: 'fas fa-exclamation-triangle',
            danger: 'fas fa-trash-alt',
            info: 'fas fa-info-circle'
        };
        
        // Crear overlay
        const overlay = document.createElement('div');
        overlay.className = 'confirm-modal-overlay';
        
        const checkboxHtml = showCheckbox ? `
            <div style="padding: 0 24px 16px; border-top: 1px solid #e2e8f0;">
                <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px; color: #64748b;">
                    <input type="checkbox" class="confirm-checkbox" style="width: 18px; height: 18px; cursor: pointer;">
                    <span>${checkboxText}</span>
                </label>
            </div>
        ` : '';
        
        overlay.innerHTML = `
            <div class="confirm-modal">
                <div class="confirm-modal-header">
                    <div class="confirm-modal-icon ${type}">
                        <i class="${icons[type] || icons.warning}"></i>
                    </div>
                    <div class="confirm-modal-text">
                        <div class="confirm-modal-title">${title}</div>
                        <div class="confirm-modal-message">${message}</div>
                    </div>
                </div>
                ${checkboxHtml}
                <div class="confirm-modal-footer">
                    <button class="btn btn-secondary confirm-cancel">${cancelText}</button>
                    <button class="btn ${confirmClass} confirm-ok">${confirmText}</button>
                </div>
            </div>
        `;
        
        document.body.appendChild(overlay);
        
        // Enfocar el botón de cancelar por defecto
        setTimeout(() => {
            overlay.querySelector('.confirm-cancel').focus();
        }, 100);
        
        // Función para cerrar y resolver
        function closeModal(confirmed) {
            overlay.style.animation = 'fadeOut 0.2s ease-out';
            setTimeout(() => {
                overlay.remove();
                resolve(confirmed);
            }, 200);
        }
        
        // Event listeners
        overlay.querySelector('.confirm-ok').addEventListener('click', () => closeModal(true));
        overlay.querySelector('.confirm-cancel').addEventListener('click', () => closeModal(false));
        
        // Cerrar al hacer clic fuera del modal
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) {
                closeModal(false);
            }
        });
        
        // Cerrar con ESC
        const escHandler = (e) => {
            if (e.key === 'Escape') {
                closeModal(false);
                document.removeEventListener('keydown', escHandler);
            }
        };
        document.addEventListener('keydown', escHandler);
    });
};

/**
 * Notificación de éxito rápida
 */
window.showSuccess = function(message, title = null) {
    return showNotification(message, 'success', 5000, title);
};

/**
 * Notificación de error rápida
 */
window.showError = function(message, title = null) {
    return showNotification(message, 'error', 7000, title);
};

/**
 * Notificación de advertencia rápida
 */
window.showWarning = function(message, title = null) {
    return showNotification(message, 'warning', 6000, title);
};

/**
 * Notificación de información rápida
 */
window.showInfo = function(message, title = null) {
    return showNotification(message, 'info', 5000, title);
};

/**
 * Función auxiliar para confirmar eliminación
 */
window.confirmDelete = async function(itemName = 'este elemento') {
    return await showConfirm({
        title: '¿Eliminar elemento?',
        message: `¿Estás seguro de que deseas eliminar ${itemName}? Esta acción no se puede deshacer.`,
        confirmText: 'Eliminar',
        cancelText: 'Cancelar',
        type: 'danger',
        confirmClass: 'btn-danger'
    });
};

/**
 * Función auxiliar para confirmar acción general
 */
window.confirmAction = async function(title, message, confirmText = 'Continuar') {
    return await showConfirm({
        title: title,
        message: message,
        confirmText: confirmText,
        cancelText: 'Cancelar',
        type: 'warning',
        confirmClass: 'btn-primary'
    });
};

// Agregar estilos de animación de salida
const style = document.createElement('style');
style.textContent = `
    @keyframes fadeOut {
        from { opacity: 1; }
        to { opacity: 0; }
    }
`;
document.head.appendChild(style);



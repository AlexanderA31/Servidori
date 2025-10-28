/**
 * Sistema de confirmaciones para formularios
 * Reemplaza los confirm() nativos con modales estilizados
 */

document.addEventListener('DOMContentLoaded', function() {
    
    // Interceptar botones con onclick que tienen confirm
    document.querySelectorAll('button[onclick*="confirm"], a[onclick*="confirm"]').forEach(element => {
        const originalOnclick = element.onclick;
        
        element.onclick = async function(e) {
            e.preventDefault();
            
            // Extraer mensaje del confirm original
            const onclickStr = element.getAttribute('onclick');
            const confirmMatch = onclickStr.match(/confirm\(['"](.+?)['"]\)/);
            
            if (!confirmMatch) {
                // Si no hay confirm, ejecutar acción original
                if (originalOnclick) originalOnclick.call(this, e);
                return false;
            }
            
            let message = confirmMatch[1];
            message = message.replace(/\\n/g, '<br>');
            
            // Determinar tipo de confirmación
            let title = '¿Estás seguro?';
            let type = 'warning';
            let confirmText = 'Confirmar';
            let confirmClass = 'btn-primary';
            
            // Detectar tipo de acción
            if (message.includes('Eliminar') || message.includes('eliminar')) {
                title = '¿Eliminar elemento?';
                type = 'danger';
                confirmText = 'Eliminar';
                confirmClass = 'btn-danger';
            } else if (message.includes('Escanear') || message.includes('escanear')) {
                title = '🌐 Escanear la red en busca de impresoras';
                type = 'info';
                confirmText = 'Iniciar Escaneo';
                confirmClass = 'btn-primary';
                message = `
                    <div style="text-align: left; line-height: 1.8;">
                        <p style="margin-bottom: 1rem;"><strong>Se buscarán impresoras en:</strong></p>
                        <ul style="list-style: none; padding-left: 0; margin-bottom: 1rem;">
                            <li style="margin: 0.5rem 0;">🌐 <strong>Redes (VLANs):</strong> 192.168.x.x, 10.0.x.x</li>
                            <li style="margin: 0.5rem 0;">🔌 <strong>USB</strong> y dispositivos compartidos</li>
                        </ul>
                        <p style="margin-top: 1rem; padding: 0.75rem; background: #fff3cd; border-radius: 6px; border-left: 3px solid #ffc107;">
                            ⏱️ <strong>Tiempo estimado:</strong> 2-5 minutos
                        </p>
                    </div>
                `;
            }
            
            // Mostrar modal de confirmación
            const confirmed = await showConfirm({
                title: title,
                message: message,
                confirmText: confirmText,
                cancelText: 'Cancelar',
                type: type,
                confirmClass: confirmClass
            });
            
            if (confirmed) {
                // Remover el onclick para evitar loop y ejecutar la acción original
                element.onclick = null;
                if (element.tagName === 'BUTTON' && element.type === 'submit') {
                    element.form.submit();
                } else if (originalOnclick) {
                    originalOnclick.call(this, e);
                }
            }
            
            return false;
        };
    });
    
    // Interceptar todos los formularios con confirmación
    document.querySelectorAll('form[onsubmit*="confirm"]').forEach(form => {
        // Guardar el handler original
        const originalOnsubmit = form.onsubmit;
        
        // Reemplazar con handler async
        form.onsubmit = async function(e) {
            e.preventDefault();
            
            // Extraer mensaje del confirm original
            const onsubmitStr = form.getAttribute('onsubmit');
            const confirmMatch = onsubmitStr.match(/confirm\(['"](.+?)['"]\)/);
            
            if (!confirmMatch) {
                // Si no hay confirm, enviar directamente
                this.submit();
                return false;
            }
            
            let message = confirmMatch[1];
            
            // Obtener el nombre del elemento desde data-name si existe
            const dataName = form.getAttribute('data-name');
            if (dataName && message.includes('this.dataset.name')) {
                // Reemplazar la referencia a this.dataset.name con el valor real
                message = message.replace(/['" ]\+\s*this\.dataset\.name\s*\+\s*['"]/, dataName);
            }
            
            // Limpiar mensaje de caracteres especiales y código residual
            message = message.replace(/\\n/g, '<br>');
            message = message.replace(/['"]\s*\+\s*this\.dataset\.[a-zA-Z]+\s*\+\s*['"]/, '...');
            
            // Determinar tipo de confirmación
            let title = '¿Estás seguro?';
            let type = 'warning';
            let confirmText = 'Confirmar';
            let confirmClass = 'btn-primary';
            
            // Detectar si es eliminación
            if (message.includes('Eliminar') || message.includes('eliminar') || onsubmitStr.includes('delete')) {
                // Intentar obtener el nombre del elemento
                const itemName = form.getAttribute('data-name') || 'este elemento';
                title = '¿Eliminar ' + (message.includes('impresora') ? 'impresora' : 
                        message.includes('departamento') ? 'departamento' : 
                        message.includes('computadora') ? 'computadora' : 'elemento') + '?';
                message = '¿Estás seguro de que deseas eliminar <strong>' + itemName + '</strong>?<br><br>Esta acción no se puede deshacer.';
                type = 'danger';
                confirmText = 'Eliminar';
                confirmClass = 'btn-danger';
            }
            // Detectar si es escaneo de red
            else if (message.includes('Escanear') || message.includes('escanear')) {
                title = '🌐 Escanear la red en busca de impresoras';
                type = 'info';
                confirmText = 'Iniciar Escaneo';
                confirmClass = 'btn-primary';
                
                // Limpiar y mejorar el mensaje para escaneo
                message = `
                    <div style="text-align: left; line-height: 1.8;">
                        <p style="margin-bottom: 1rem;"><strong>Se buscarán impresoras en:</strong></p>
                        <ul style="list-style: none; padding-left: 0; margin-bottom: 1rem;">
                            <li style="margin: 0.5rem 0;">🌐 <strong>Redes (VLANs):</strong> 192.168.x.x, 10.0.x.x</li>
                            <li style="margin: 0.5rem 0;">🔌 <strong>USB</strong> y dispositivos compartidos</li>
                        </ul>
                        <p style="margin-top: 1rem; padding: 0.75rem; background: #fff3cd; border-radius: 6px; border-left: 3px solid #ffc107;">
                            ⏱️ <strong>Tiempo estimado:</strong> 2-5 minutos
                        </p>
                    </div>
                `;
            }
            // Detectar si es prueba
            else if (message.includes('prueba') || message.includes('Probar') || message.includes('Enviar')) {
                const printerName = form.getAttribute('data-name') || 'la impresora';
                title = '🖨️ Enviar página de prueba';
                message = '¿Deseas enviar una página de prueba a <strong>' + printerName + '</strong>?';
                type = 'info';
                confirmText = 'Enviar';
                confirmClass = 'btn-primary';
            }
            // Detectar si es cancelación
            else if (message.includes('Cancelar') || message.includes('cancelar')) {
                const jobName = form.getAttribute('data-name') || 'este trabajo';
                title = '⚠️ Cancelar trabajo';
                message = '¿Estás seguro de que deseas cancelar <strong>' + jobName + '</strong>?';
                type = 'warning';
                confirmText = 'Cancelar trabajo';
                confirmClass = 'btn-danger';
            }
            // Detectar si es limpiar
            else if (message.includes('Limpiar') || message.includes('limpiar')) {
                const queueName = form.getAttribute('data-name') || 'toda la cola';
                title = '🧼 Limpiar cola de impresión';
                message = '¿Estás seguro de que deseas limpiar <strong>' + queueName + '</strong>?<br><br>Se eliminarán todos los trabajos pendientes.';
                type = 'warning';
                confirmText = 'Limpiar';
                confirmClass = 'btn-danger';
            }
            // Detectar si es desasignar
            else if (message.includes('Desasignar') || message.includes('desasignar')) {
                title = 'Desasignar del departamento';
                message = '¿Deseas desasignar esta computadora del departamento?';
                type = 'warning';
                confirmText = 'Desasignar';
                confirmClass = 'btn-primary';
            }
            
            // Mostrar modal de confirmación
            const confirmed = await showConfirm({
                title: title,
                message: message,
                confirmText: confirmText,
                cancelText: 'Cancelar',
                type: type,
                confirmClass: confirmClass
            });
            
            if (confirmed) {
                // Remover el handler para evitar loop
                form.onsubmit = null;
                form.submit();
            }
            
            return false;
        };
    });
    
    // Convertir alertas del servidor en notificaciones
    convertServerAlertsToNotifications();
});

/**
 * Convertir alertas del servidor en notificaciones estilizadas
 */
function convertServerAlertsToNotifications() {
    const alerts = document.querySelectorAll('.alert-fixed');
    
    alerts.forEach(alert => {
        const message = alert.textContent.trim();
        let type = 'info';
        
        if (alert.classList.contains('alert-success')) {
            type = 'success';
        } else if (alert.classList.contains('alert-error')) {
            type = 'error';
        } else if (alert.classList.contains('alert-warning')) {
            type = 'warning';
        }
        
        // Ocultar alerta original
        alert.style.display = 'none';
        
        // Mostrar notificación
        setTimeout(() => {
            showNotification(message, type);
        }, 100);
    });
}



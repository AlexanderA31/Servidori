/**
 * Monitor Global de Escaneo de Red
 * Permite que la barra minimizada persista entre secciones del admin
 */

(function() {
    let globalScanInterval = null;
    
    // Función para actualizar el progreso de la barra minimizada desde cualquier página
    window.updateScanProgressGlobal = function() {
        // Solo ejecutar si la barra existe en el DOM
        const minimizedBar = document.getElementById('scanProgressMinimized');
        if (!minimizedBar) return;
        
        // Obtener estado del escaneo
        fetch('/admin/scan-status')
            .then(response => response.json())
            .then(data => {
                // Si no hay escaneo activo y progreso es 0, detener
                if (!data.scanning && data.progress === 0) {
                    stopGlobalMonitoring();
                    return;
                }
                
                // Actualizar barra minimizada
                const progressBar = document.getElementById('scanProgressMinimizedBar');
                const progressPercent = document.getElementById('scanProgressMinimizedPercent');
                const networkSpan = document.getElementById('scanMinimizedNetwork');
                const printersSpan = document.getElementById('scanMinimizedPrinters');
                
                if (progressBar) progressBar.style.width = data.progress + '%';
                if (progressPercent) progressPercent.textContent = data.progress + '%';
                if (networkSpan) networkSpan.textContent = data.currentNetwork || 'Iniciando...';
                if (printersSpan) printersSpan.textContent = data.foundPrinters + ' impresoras';
                
                // Guardar estado en sessionStorage
                const state = {
                    isMinimized: true,
                    scanning: data.scanning,
                    cancelled: data.cancelled,
                    progress: data.progress,
                    currentNetwork: data.currentNetwork,
                    foundPrinters: data.foundPrinters,
                    timestamp: Date.now()
                };
                sessionStorage.setItem('scanMinimizedState', JSON.stringify(state));
                
                // Si el escaneo fue CANCELADO
                if (data.cancelled && !data.scanning) {
                
                    stopGlobalMonitoring();
                    sessionStorage.removeItem('scanMinimizedState');
                    
                    // Ocultar barra con animación
                    minimizedBar.style.opacity = '0';
                    setTimeout(() => {
                        minimizedBar.style.display = 'none';
                        minimizedBar.style.opacity = '1';
                    }, 300);
                    return;
                }
                
                // Si el escaneo terminó normalmente
                if (data.progress >= 100 || (!data.scanning && data.progress > 0)) {
                    stopGlobalMonitoring();
                    sessionStorage.removeItem('scanMinimizedState');
                    
                    // Ocultar barra con animación
                    minimizedBar.style.opacity = '0';
                    setTimeout(() => {
                        minimizedBar.style.display = 'none';
                        minimizedBar.style.opacity = '1';
                    }, 300);
                    
                    // Notificar al usuario
                
                }
            })
            .catch(error => {
                console.error('Error al obtener estado del escaneo:', error);
            });
    };
    
    // Iniciar monitoreo global
    function startGlobalMonitoring() {
        if (globalScanInterval) return; // Ya está corriendo
        
        globalScanInterval = setInterval(window.updateScanProgressGlobal, 2000); // Cada 2 segundos
        window.updateScanProgressGlobal(); // Primera actualización inmediata
    }
    
    // Detener monitoreo global
    function stopGlobalMonitoring() {
        if (globalScanInterval) {
            clearInterval(globalScanInterval);
            globalScanInterval = null;
        }
    }
    
    // Inicializar al cargar la página
    document.addEventListener('DOMContentLoaded', function() {
        const scanState = sessionStorage.getItem('scanMinimizedState');
        
        if (scanState) {
            try {
                const state = JSON.parse(scanState);
                
                // Verificar que el estado no sea muy viejo (más de 5 minutos)
                const now = Date.now();
                const stateAge = now - (state.timestamp || 0);
                if (stateAge > 5 * 60 * 1000) {
                    // Estado muy viejo, limpiar
                    sessionStorage.removeItem('scanMinimizedState');
                    return;
                }
                
                // Si el escaneo está activo y minimizado
                if (state.isMinimized && state.scanning) {
                    const minimizedBar = document.getElementById('scanProgressMinimized');
                    if (minimizedBar) {
                        // Obtener estado actual del servidor (más confiable)
                        fetch('/admin/scan-status')
                            .then(response => response.json())
                            .then(data => {
                                // Si el escaneo ya no está activo, no mostrar la barra
                                if (!data.scanning && data.progress === 0) {
                                    sessionStorage.removeItem('scanMinimizedState');
                                    return;
                                }
                                
                                // Mostrar la barra minimizada con datos actuales del servidor
                                minimizedBar.style.display = 'block';
                                
                                const progressBar = document.getElementById('scanProgressMinimizedBar');
                                const progressPercent = document.getElementById('scanProgressMinimizedPercent');
                                const networkSpan = document.getElementById('scanMinimizedNetwork');
                                const printersSpan = document.getElementById('scanMinimizedPrinters');
                                
                                if (progressBar) progressBar.style.width = data.progress + '%';
                                if (progressPercent) progressPercent.textContent = data.progress + '%';
                                if (networkSpan) networkSpan.textContent = data.currentNetwork || 'Iniciando...';
                                if (printersSpan) printersSpan.textContent = data.foundPrinters + ' impresoras';
                                
                                // Iniciar monitoreo continuo
                                startGlobalMonitoring();
                                
                                // Si el escaneo ya terminó, ocultar después de un momento
                                if (!data.scanning && data.progress >= 100) {
                                    setTimeout(() => {
                                        minimizedBar.style.display = 'none';
                                        sessionStorage.removeItem('scanMinimizedState');
                                        stopGlobalMonitoring();
                                    }, 3000);
                                }
                            })
                            .catch(error => {
                                console.error('Error al verificar estado del escaneo:', error);
                                // Si hay error, usar el estado guardado como fallback
                                minimizedBar.style.display = 'block';
                                
                                const progressBar = document.getElementById('scanProgressMinimizedBar');
                                const progressPercent = document.getElementById('scanProgressMinimizedPercent');
                                const networkSpan = document.getElementById('scanMinimizedNetwork');
                                const printersSpan = document.getElementById('scanMinimizedPrinters');
                                
                                if (progressBar && state.progress !== undefined) {
                                    progressBar.style.width = state.progress + '%';
                                }
                                if (progressPercent && state.progress !== undefined) {
                                    progressPercent.textContent = state.progress + '%';
                                }
                                if (networkSpan && state.currentNetwork) {
                                    networkSpan.textContent = state.currentNetwork;
                                }
                                if (printersSpan && state.foundPrinters !== undefined) {
                                    printersSpan.textContent = state.foundPrinters + ' impresoras';
                                }
                                
                                startGlobalMonitoring();
                            });
                    }
                }
            } catch (e) {
                console.error('Error al restaurar estado de escaneo:', e);
                sessionStorage.removeItem('scanMinimizedState');
            }
        }
    });
    
    // Limpiar interval al salir de la página
    window.addEventListener('beforeunload', function() {
        // NO limpiar el sessionStorage aquí, solo el interval
        stopGlobalMonitoring();
    });
})();

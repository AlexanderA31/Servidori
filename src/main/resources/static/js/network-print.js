// Funciones para gestión de red de impresión

function editComputer(data) {
    document.getElementById('editComputerId').value = data.id;
    document.getElementById('editComputerMac').value = data.mac;
    document.getElementById('editComputerName').value = data.name;
    document.getElementById('editComputerHostname').value = data.hostname;
    document.getElementById('editComputerLocation').value = data.location;
    openModal('editComputerModal');
}

function openAssignPrinterModal(data) {
    document.getElementById('assignComputerId').value = data.id;
    document.getElementById('assignComputerInfo').innerHTML = `
        <div><strong>${data.name}</strong></div>
        <div class="mac-address">${data.mac}</div>
    `;
    openModal('assignPrinterModal');
}

// Auto-cerrar alertas después de 5 segundos
document.addEventListener('DOMContentLoaded', function() {
    const alerts = document.querySelectorAll('.alert');
    alerts.forEach(alert => {
        setTimeout(() => {
            alert.style.opacity = '0';
            setTimeout(() => alert.remove(), 300);
        }, 5000);
    });
});

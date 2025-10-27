// Admin Panel JavaScript

document.addEventListener('DOMContentLoaded', function() {
    // Sidebar navigation switching
    const navLinks = document.querySelectorAll('.sidebar-nav-link, .admin-tab');
    const tabContents = document.querySelectorAll('.tab-content');
    
    navLinks.forEach(link => {
        link.addEventListener('click', function() {
            const target = this.dataset.tab;
            
            // Remove active class from all links and contents
            navLinks.forEach(l => l.classList.remove('active'));
            tabContents.forEach(tc => tc.classList.remove('active'));
            
            // Add active class to clicked link and corresponding content
            this.classList.add('active');
            const targetElement = document.getElementById(target);
            if (targetElement) {
                targetElement.classList.add('active');
            }
        });
    });
    
    // Modal handling
    window.openModal = function(modalId) {
        document.getElementById(modalId).classList.add('show');
    };
    
    window.closeModal = function(modalId) {
        document.getElementById(modalId).classList.remove('show');
    };
    
    // Close modal on outside click
    document.querySelectorAll('.modal').forEach(modal => {
        modal.addEventListener('click', function(e) {
            if (e.target === this) {
                this.classList.remove('show');
            }
        });
    });
    
    // Edit functions
    window.editUser = function(id, username, roles) {
        document.getElementById('editUserId').value = id;
        document.getElementById('editUsername').value = username;
        document.getElementById('editRoles').value = roles;
        openModal('editUserModal');
    };
    
    window.editPrinter = function(id, alias, model, location, ip) {
        document.getElementById('editPrinterId').value = id;
        document.getElementById('editAlias').value = alias;
        document.getElementById('editModel').value = model;
        document.getElementById('editLocation').value = location;
        document.getElementById('editIp').value = ip;
        openModal('editPrinterModal');
    };
    
    window.editGroup = function(id, name) {
        document.getElementById('editGroupId').value = id;
        document.getElementById('editGroupName').value = name;
        openModal('editGroupModal');
    };
    
    window.openAddPrinterToGroupModal = function(id, name) {
        // Esta función se puede implementar después para agregar impresoras a grupos
        alert('Función para agregar impresoras al grupo "' + name + '" estará disponible próximamente');
    };
    
    // Delete confirmations
    window.confirmDelete = function(form) {
        return confirm('¿Está seguro de que desea eliminar este elemento?');
    };
    
    // Auto-hide alerts after 5 seconds
    setTimeout(() => {
        document.querySelectorAll('.alert').forEach(alert => {
            alert.style.opacity = '0';
            setTimeout(() => alert.remove(), 300);
        });
    }, 5000);
});

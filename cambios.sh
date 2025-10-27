# Crear script de recompilación directa
cat > ~/recompile-and-apply.sh << 'ENDOFSCRIPT'
#!/bin/bash

##############################################################################
# Script de Recompilación y Aplicación Directa
# Recompila el código actual y aplica cambios sin Git
##############################################################################

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_step() {
    echo -e "${BLUE}▶${NC} $1"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

echo ""
echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Recompilando y Aplicando Cambios                         ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# 1. Ir al directorio del proyecto
print_step "Navegando al directorio del proyecto..."
cd ~/Servidori
print_success "Directorio: $(pwd)"

# 2. Limpiar compilaciones anteriores
print_step "Limpiando compilaciones anteriores..."
rm -rf target
print_success "Directorio target limpio"

# 3. Compilar el proyecto
print_step "Compilando proyecto (1-2 minutos)..."
echo "  Esto puede tardar un poco, por favor espera..."
if mvn clean package -DskipTests -q; then
    print_success "Compilación exitosa"
else
    print_error "Error en la compilación"
    echo ""
    echo "Ejecuta para ver el error completo:"
    echo "  cd ~/Servidori && mvn clean package -DskipTests"
    exit 1
fi

# 4. Verificar que el JAR se creó
if [ -f "target/iu-0.0.1-SNAPSHOT.jar" ]; then
    JAR_SIZE=$(du -h target/iu-0.0.1-SNAPSHOT.jar | cut -f1)
    print_success "JAR generado: $JAR_SIZE"
else
    print_error "JAR no encontrado en target/"
    exit 1
fi

# 5. Detener el servicio
print_step "Deteniendo servicio print-manager..."
sudo systemctl stop print-manager
print_success "Servicio detenido"

# 6. Hacer backup del JAR anterior (por seguridad)
print_step "Haciendo backup del JAR anterior..."
if [ -f "/opt/print-manager/target/iu-0.0.1-SNAPSHOT.jar" ]; then
    sudo cp /opt/print-manager/target/iu-0.0.1-SNAPSHOT.jar \
             /opt/print-manager/target/iu-0.0.1-SNAPSHOT.jar.backup
    print_success "Backup creado"
else
    print_step "No hay JAR anterior para backup"
fi

# 7. Copiar archivos compilados
print_step "Copiando archivos compilados..."
sudo rm -rf /opt/print-manager/target
sudo cp -r target /opt/print-manager/
print_success "Archivos del target copiados"

# 8. Copiar recursos actualizados
print_step "Copiando recursos (templates, CSS, JS)..."
sudo cp -r src/main/resources/* /opt/print-manager/src/main/resources/
print_success "Recursos actualizados"

# 9. Ajustar permisos
print_step "Ajustando permisos..."
sudo chown -R printmgr:printmgr /opt/print-manager
print_success "Permisos configurados"

# 10. Iniciar el servicio
print_step "Iniciando servicio print-manager..."
sudo systemctl start print-manager
print_success "Servicio iniciado"

# 11. Esperar a que inicie completamente
print_step "Esperando inicio de la aplicación (15 segundos)..."
for i in {15..1}; do
    echo -ne "  Esperando... $i segundos restantes\r"
    sleep 1
done
echo ""
print_success "Tiempo de espera completado"

# 12. Verificar estado del servicio
print_step "Verificando estado del servicio..."
if sudo systemctl is-active --quiet print-manager; then
    print_success "Servicio activo y corriendo"
else
    print_error "El servicio no está activo"
    echo ""
    echo "Ver logs con:"
    echo "  sudo journalctl -u print-manager -n 50 --no-pager"
    exit 1
fi

# 13. Verificar que la aplicación responde
print_step "Verificando que la aplicación responde..."
sleep 5
if curl -s http://localhost:8080 > /dev/null 2>&1; then
    print_success "Aplicación respondiendo correctamente"
else
    print_error "La aplicación no responde en el puerto 8080"
    echo "  Puede estar iniciando aún, espera 30 segundos más"
fi

# 14. Verificar la IP del servidor
print_step "Verificando IP del servidor..."
sleep 3
SERVER_IP=$(curl -s http://localhost:8080/print-server/api/printers 2>/dev/null | grep -o '"serverIp":"[^"]*"' | cut -d'"' -f4)

if [ -n "$SERVER_IP" ]; then
    if [ "$SERVER_IP" = "127.0.1.1" ] || [ "$SERVER_IP" = "127.0.0.1" ]; then
        print_error "⚠ Todavía usando IP de loopback: $SERVER_IP"
        echo ""
        echo "Posibles causas:"
        echo "  1. El código no tiene los cambios de NetworkUtils.getServerIpAddress()"
        echo "  2. PrinterServerController no está usando el nuevo método"
        echo ""
        echo "Verifica con:"
        echo "  grep 'getServerIpAddress' ~/Servidori/src/main/java/es/ucm/fdi/iu/util/NetworkUtils.java"
    else
        print_success "✓ IP del servidor correcta: $SERVER_IP"
    fi
else
    print_error "No se pudo obtener la IP del servidor (aplicación aún iniciando)"
    echo "  Espera 30 segundos y ejecuta:"
    echo "  curl http://localhost:8080/print-server/api/printers | grep serverIp"
fi

echo ""
echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  Recompilación y Aplicación Completada                    ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Información del sistema
IP_REAL=$(hostname -I | awk '{print $1}')
echo "Información del sistema:"
echo "  • IP del servidor (sistema): $IP_REAL"
if [ -n "$SERVER_IP" ]; then
    echo "  • IP reportada por app:      $SERVER_IP"
fi
echo "  • Puerto:                    8080"
echo "  • URL:                       http://$IP_REAL:8080"
echo "  • JAR:                       /opt/print-manager/target/iu-0.0.1-SNAPSHOT.jar"
echo ""
echo "Comandos útiles:"
echo "  • Ver logs:      sudo journalctl -u print-manager -f"
echo "  • Ver estado:    sudo systemctl status print-manager"
echo "  • Reiniciar:     sudo systemctl restart print-manager"
echo "  • Verificar IP:  curl http://localhost:8080/print-server/api/printers | grep serverIp"
echo ""

# Ofrecer ver logs
read -p "¿Deseas ver los últimos logs? (s/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Ss]$ ]]; then
    echo ""
    echo "Mostrando últimos 30 logs (Ctrl+C para salir):"
    echo "─────────────────────────────────────────────────────────────"
    sudo journalctl -u print-manager -n 30 --no-pager
fi

echo ""
ENDOFSCRIPT

# Dar permisos de ejecución
chmod +x ~/recompile-and-apply.sh

echo "✓ Script creado: ~/recompile-and-apply.sh"
echo ""
echo "Para usar el script, ejecuta:"
echo "  ~/recompile-and-apply.sh"
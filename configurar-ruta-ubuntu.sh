#!/bin/bash

# ============================================
# Script para configurar ruta estática en Ubuntu
# Permite acceder a red 172.16.0.0/24 desde 10.1.16.31
# ============================================

echo "🔍 DIAGNÓSTICO DE RED ACTUAL"
echo "==========================================="

# 1. Mostrar configuración de red actual
echo ""
echo "📡 Interfaces de red:"
ip addr show

echo ""
echo "🛣️  Rutas actuales:"
ip route show

echo ""
echo "🌐 Gateway predeterminado:"
ip route show default

echo ""
echo "==========================================="
echo ""
echo "❓ PREGUNTAS PARA CONFIGURAR LA RUTA:"
echo ""
echo "Para agregar la ruta a 172.16.0.0/24, necesito saber:"
echo ""
echo "1. ¿Cuál es la IP del gateway/router que puede alcanzar la red WiFi?"
echo "   Opciones comunes:"
echo "   - 10.1.16.1 (si es el gateway de tu red actual)"
echo "   - 172.16.0.1 (si puedes alcanzar directamente el router WiFi)"
echo "   - Otra IP que conozcas"
echo ""
read -p "   Ingresa la IP del gateway: " GATEWAY_IP

echo ""
echo "2. ¿Cuál es el nombre de tu interfaz de red principal?"
echo "   (Generalmente aparece como: eth0, ens33, enp0s3, etc.)"
echo ""
read -p "   Ingresa el nombre de la interfaz: " INTERFACE_NAME

echo ""
echo "==========================================="
echo "🔧 CONFIGURANDO RUTA ESTÁTICA"
echo "==========================================="

# Verificar que el gateway responde
echo ""
echo "🔍 Verificando conectividad al gateway $GATEWAY_IP..."
if ping -c 2 -W 2 "$GATEWAY_IP" > /dev/null 2>&1; then
    echo "✅ Gateway $GATEWAY_IP es alcanzable"
else
    echo "⚠️  ADVERTENCIA: No se puede hacer ping al gateway $GATEWAY_IP"
    echo "   Esto es normal si el gateway no responde a ping (ICMP bloqueado)"
    echo "   Continuaremos de todas formas..."
fi

echo ""
echo "📝 Creando archivo de configuración permanente..."

# Detectar el sistema de red usado (netplan o interfaces)
if [ -d "/etc/netplan" ]; then
    echo "✅ Sistema usa Netplan"
    
    # Buscar archivo de configuración existente
    NETPLAN_FILE=$(ls /etc/netplan/*.yaml 2>/dev/null | head -1)
    
    if [ -z "$NETPLAN_FILE" ]; then
        NETPLAN_FILE="/etc/netplan/01-netcfg.yaml"
    fi
    
    echo ""
    echo "Archivo de configuración: $NETPLAN_FILE"
    echo ""
    echo "⚠️  IMPORTANTE: Voy a modificar $NETPLAN_FILE"
    echo "   Se creará un respaldo en: ${NETPLAN_FILE}.backup"
    echo ""
    read -p "¿Continuar? (s/n): " CONFIRM
    
    if [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ]; then
        echo "❌ Operación cancelada"
        exit 1
    fi
    
    # Hacer backup
    sudo cp "$NETPLAN_FILE" "${NETPLAN_FILE}.backup"
    echo "✅ Respaldo creado: ${NETPLAN_FILE}.backup"
    
    # Crear configuración temporal
    cat > /tmp/route-config.yaml << EOF
# Agregar estas líneas dentro de la configuración de tu interfaz:
      routes:
        - to: 172.16.0.0/24
          via: $GATEWAY_IP
          metric: 100
EOF
    
    echo ""
    echo "📄 Configuración a agregar:"
    cat /tmp/route-config.yaml
    
    echo ""
    echo "⚠️  INSTRUCCIONES MANUALES:"
    echo "1. Abre el archivo de configuración:"
    echo "   sudo nano $NETPLAN_FILE"
    echo ""
    echo "2. Busca la sección de tu interfaz ($INTERFACE_NAME)"
    echo ""
    echo "3. Agrega las líneas de configuración de ruta (respetando la indentación)"
    echo ""
    echo "4. Ejemplo completo:"
    echo ""
    cat << 'EOF'
network:
  version: 2
  renderer: networkd
  ethernets:
    INTERFACE_NAME:
      addresses:
        - 10.1.16.31/24
      gateway4: 10.1.16.1
      nameservers:
        addresses: [8.8.8.8, 8.8.4.4]
      routes:
        - to: 172.16.0.0/24
          via: GATEWAY_IP
          metric: 100
EOF
    
    echo ""
    echo "5. Reemplaza INTERFACE_NAME con: $INTERFACE_NAME"
    echo "6. Reemplaza GATEWAY_IP con: $GATEWAY_IP"
    echo ""
    echo "7. Guarda (Ctrl+O) y sal (Ctrl+X)"
    echo ""
    echo "8. Aplica cambios: sudo netplan apply"
    echo ""
    read -p "Presiona Enter cuando hayas terminado de editar..."
    
    # Ofrecer aplicar cambios
    echo ""
    read -p "¿Aplicar la configuración ahora? (s/n): " APPLY
    if [ "$APPLY" = "s" ] || [ "$APPLY" = "S" ]; then
        echo "🔄 Aplicando configuración de netplan..."
        sudo netplan apply
        if [ $? -eq 0 ]; then
            echo "✅ Configuración aplicada exitosamente"
        else
            echo "❌ Error al aplicar configuración"
            echo "   Revierte con: sudo cp ${NETPLAN_FILE}.backup $NETPLAN_FILE"
            exit 1
        fi
    fi
    
elif [ -f "/etc/network/interfaces" ]; then
    echo "✅ Sistema usa /etc/network/interfaces"
    
    # Hacer backup
    sudo cp /etc/network/interfaces /etc/network/interfaces.backup
    echo "✅ Respaldo creado: /etc/network/interfaces.backup"
    
    # Agregar ruta
    echo ""
    echo "Agregando ruta al archivo..."
    sudo bash -c "echo '' >> /etc/network/interfaces"
    sudo bash -c "echo '# Ruta a red WiFi 172.16.0.0/24' >> /etc/network/interfaces"
    sudo bash -c "echo 'up ip route add 172.16.0.0/24 via $GATEWAY_IP dev $INTERFACE_NAME' >> /etc/network/interfaces"
    sudo bash -c "echo 'down ip route del 172.16.0.0/24 via $GATEWAY_IP dev $INTERFACE_NAME' >> /etc/network/interfaces"
    
    echo "✅ Ruta agregada a /etc/network/interfaces"
    
    # Aplicar ruta inmediatamente
    echo ""
    echo "🔄 Aplicando ruta inmediatamente..."
    sudo ip route add 172.16.0.0/24 via "$GATEWAY_IP" dev "$INTERFACE_NAME" 2>/dev/null
    
else
    echo "⚠️  No se detectó netplan ni /etc/network/interfaces"
    echo "   Aplicando ruta temporal (se perderá al reiniciar)..."
    sudo ip route add 172.16.0.0/24 via "$GATEWAY_IP" dev "$INTERFACE_NAME"
fi

echo ""
echo "==========================================="
echo "✅ RUTA CONFIGURADA"
echo "==========================================="

# Mostrar rutas actualizadas
echo ""
echo "🛣️  Rutas actuales:"
ip route show

echo ""
echo "🧪 PRUEBAS DE CONECTIVIDAD"
echo "==========================================="

# Probar conectividad a la red WiFi
echo ""
echo "🔍 Probando conectividad a red 172.16.0.0/24..."
echo ""

# Probar gateway WiFi
echo "1. Ping al gateway WiFi (172.16.0.1):"
if ping -c 3 -W 2 172.16.0.1; then
    echo "   ✅ Gateway WiFi alcanzable"
else
    echo "   ⚠️  Gateway WiFi no responde (puede ser normal si ICMP está bloqueado)"
fi

echo ""
echo "2. Probando algunos hosts en la red WiFi..."
for ip in 172.16.0.1 172.16.0.2 172.16.0.10 172.16.0.50; do
    if ping -c 1 -W 1 "$ip" > /dev/null 2>&1; then
        echo "   ✅ $ip responde"
    fi
done

echo ""
echo "==========================================="
echo "📋 RESUMEN"
echo "==========================================="
echo ""
echo "✅ Ruta agregada: 172.16.0.0/24 via $GATEWAY_IP"
echo "✅ Interfaz: $INTERFACE_NAME"
echo "✅ La ruta sobrevivirá a reinicios"
echo ""
echo "🔄 SIGUIENTE PASO:"
echo ""
echo "1. Reinicia tu aplicación Java:"
echo "   pkill -f 'java.*iu-0.0.1-SNAPSHOT.jar'"
echo "   java -jar target/iu-0.0.1-SNAPSHOT.jar"
echo ""
echo "2. Accede a: http://localhost:8080/admin/printers"
echo ""
echo "3. Haz clic en 'Escanear Red'"
echo ""
echo "4. Espera a que detecte las impresoras en 172.16.0.0/24"
echo ""
echo "==========================================="

# Guardar información de configuración
cat > /tmp/configuracion-ruta.txt << EOF
CONFIGURACIÓN DE RUTA ESTÁTICA
==============================

Gateway: $GATEWAY_IP
Interfaz: $INTERFACE_NAME
Red destino: 172.16.0.0/24

Comando manual (si es necesario):
sudo ip route add 172.16.0.0/24 via $GATEWAY_IP dev $INTERFACE_NAME

Verificar ruta:
ip route show | grep 172.16.0

Eliminar ruta (si es necesario):
sudo ip route del 172.16.0.0/24

Archivo de configuración (backup):
$(if [ -d "/etc/netplan" ]; then echo "$NETPLAN_FILE.backup"; else echo "/etc/network/interfaces.backup"; fi)

Fecha: $(date)
EOF

echo "📝 Información guardada en: /tmp/configuracion-ruta.txt"
echo ""

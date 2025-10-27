package es.ucm.fdi.iu.repository;

import es.ucm.fdi.iu.model.Printer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Query;

/**
 * Repositorio para gestionar impresoras
 */
@Repository
public interface PrinterRepository extends JpaRepository<Printer, Long> {
    
    /**
     * Busca una impresora por su alias
     */
    Optional<Printer> findByAlias(String alias);
    
    /**
     * Busca impresoras por IP
     */
    Optional<Printer> findByIp(String ip);
    
    /**
     * Lista todas las impresoras activas ordenadas por ID (para mantener orden consistente)
     */
    List<Printer> findAllByOrderByIdAsc();
    
    /**
     * Lista todas las impresoras - mantener compatibilidad
     */
    List<Printer> findAll();
    
    /**
     * Encuentra la primera impresora sin puerto IPP asignado
     */
    List<Printer> findByIppPortIsNull();
    
    /**
     * Obtiene el puerto IPP máximo asignado
     */
    @Query("SELECT MAX(p.ippPort) FROM Printer p WHERE p.ippPort IS NOT NULL")
    Integer findMaxIppPort();
}

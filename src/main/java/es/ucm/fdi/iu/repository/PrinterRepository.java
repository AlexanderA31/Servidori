package es.ucm.fdi.iu.repository;

import es.ucm.fdi.iu.model.Printer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

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
     * Lista todas las impresoras activas
     */
    List<Printer> findAll();
}

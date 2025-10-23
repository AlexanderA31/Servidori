package es.ucm.fdi.iu.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;


/**
 * An authorization token; like a session, but for APIs
 * THIS IS ONLY DEMO CODE - IT IS NOT SPECIALLY SECURE. USE OAUTH FOR REAL STUFF
 */
@Entity
@Data
@NoArgsConstructor
public class Token {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
	private long id;

    private String key;

    @ManyToOne
    private User user;
}

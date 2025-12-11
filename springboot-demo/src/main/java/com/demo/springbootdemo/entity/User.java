package com.demo.springbootdemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Setter @Getter
@Table(name = "`user`")
public class User {
    @Id private String email;
    @JsonIgnore String password;
    private String firstname;
    private String lastname;
    private Long dateOfBirth;
    private Long  creationDate;
    private Long  lastPasswordResetDate;
    private boolean firstLogin;
    private boolean locked;
    private String profilePictureUrl;
    private String profilePicturePublicId;
    private int attempts;
    private String verificationCode;
    private String degree;
    private String address;
    private String city;
    private String country;
    private String postCode;
    private String title;
    private Float holidaySold = 28F;
    private Float sicknessLeaverSold = 10F;
    @Enumerated(EnumType.STRING)
    private Role role;

    @ManyToOne()
    @JoinColumn(name = "company", nullable = false)
    private Company company;

    @ManyToOne
    @JoinColumn(name = "team")
    private Team team;

    @OneToMany(mappedBy = "manager")
    private List<Team> teams = new ArrayList<>();

    @OneToMany(mappedBy = "from")
    @JsonIgnore
    private List<Notification> notificationsFrom = new ArrayList<>();

    @OneToMany(mappedBy = "to")
    @JsonIgnore
    private List<Notification> notificationsTo = new ArrayList<>();

    @OneToMany(mappedBy = "to")
    @JsonIgnore
    private List<Document> documentsTo = new ArrayList<>();

    @OneToMany(mappedBy = "from")
    @JsonIgnore
    private List<Document> documentsFrom = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    @JsonIgnore
    private List<Holiday> holidays = new ArrayList<>();

    @ManyToMany(mappedBy = "participants")
    @JsonIgnore
    private List<Event> events = new ArrayList<>();

    public void setTeams(Team team){
        this.teams.add(team);
    }
    public void setDocumentsTo(Document documentsTo){
        this.documentsTo.add(documentsTo);
    }
    public void setDocumentsFrom(Document documentsFrom){
        this.documentsTo.add(documentsFrom);
    }
    public void setEvents(Event event){
        this.events.add(event);
    }
}

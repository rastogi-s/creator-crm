package com.creatorcrm.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "app_state")
public class AppState {
    @Id public String stateKey;
    public String stateValue;
}

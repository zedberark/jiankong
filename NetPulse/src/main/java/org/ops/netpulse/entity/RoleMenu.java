package org.ops.netpulse.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "role_menu", uniqueConstraints = @UniqueConstraint(columnNames = { "role_id", "menu_code" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "menu_code", nullable = false, length = 64)
    private String menuCode;
}

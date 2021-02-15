package de.fraunhofer.isst.dataspaceconnector.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class RepresentationDesc extends BaseDescription<Representation> {
    private String title;
    private String type;
    private String language;
}
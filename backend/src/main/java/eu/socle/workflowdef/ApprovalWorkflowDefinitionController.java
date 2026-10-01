// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.workflowdef;

import eu.socle.workflowdef.WorkflowDefinitionDtos.DefinitionView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.GlobalRoleView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.UpsertRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD des définitions d'approbation (étapes, SLA, rôles, scopes).
 */
@RestController
@RequestMapping("/api/v1/approval-workflows")
public class ApprovalWorkflowDefinitionController {

    private final ApprovalWorkflowDefinitionService service;

    public ApprovalWorkflowDefinitionController(ApprovalWorkflowDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public List<DefinitionView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public DefinitionView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionView create(@Valid @RequestBody UpsertRequest body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public DefinitionView update(@PathVariable UUID id, @Valid @RequestBody UpsertRequest body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

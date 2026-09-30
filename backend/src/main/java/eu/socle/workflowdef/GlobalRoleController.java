package eu.socle.workflowdef;

import eu.socle.workflowdef.WorkflowDefinitionDtos.GlobalRoleView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Liste des rôles globaux — sélecteur approbateur dans l'admin workflows. */
@RestController
@RequestMapping("/api/v1/global-roles")
public class GlobalRoleController {

    private final ApprovalWorkflowDefinitionService service;

    public GlobalRoleController(ApprovalWorkflowDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public List<GlobalRoleView> list() {
        return service.listGlobalRoles();
    }
}

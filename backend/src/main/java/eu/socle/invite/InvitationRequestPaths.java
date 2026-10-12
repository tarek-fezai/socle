// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class InvitationRequestPaths {

    public static final String ACCEPT = "/api/v1/invitations/accept";

    private InvitationRequestPaths() {}

    public static boolean isAcceptRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null && isAcceptRequest(attrs.getRequest());
    }

    public static boolean isAcceptRequest(HttpServletRequest request) {
        if (request == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return ACCEPT.equals(path);
    }
}

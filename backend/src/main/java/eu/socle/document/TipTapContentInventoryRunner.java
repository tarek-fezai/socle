// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Au démarrage : inventaire TipTap en WARN si des corps sont non conformes.
 * Ne bloque jamais le démarrage.
 */
@Component
@Order(1000)
public class TipTapContentInventoryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TipTapContentInventoryRunner.class);

    private final TipTapContentInventory inventory;

    public TipTapContentInventoryRunner(TipTapContentInventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            inventory.scanDatabase();
        } catch (RuntimeException e) {
            log.warn("TipTap inventaire au démarrage échoué (non bloquant) : {}", e.getMessage());
        }
    }
}

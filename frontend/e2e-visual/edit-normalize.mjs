// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Normalisation N1–N10 de Edit.dc.html — documentée dans l'en-tête de edit-visual.spec.mjs.
 * À appeler APRÈS annotateEditMockup (les annotations se font sur la structure d'origine).
 */
export async function normalizeEditMockup(page) {
  await page.evaluate(() => {
    const hide = (el) => {
      if (el) el.style.display = 'none'
    }
    const root = document.querySelector('body div[style*="1440px"]') || document.body.firstElementChild
    const main = root.children[1]
    const [topbar, banner, , row] = [...main.children]

    // N1 — bandeau de co-édition
    hide(banner)

    // N2 — présence : un seul avatar (utilisateur courant)
    const presence = topbar.children[1].children[0]
    presence.children[0]?.remove()
    if (presence.children[0]) presence.children[0].textContent = 'TF'

    // N4 — poignées de bloc
    document.querySelectorAll('.block-handles').forEach(hide)

    const col = row.children[0].children[0]
    const k = [...col.children]
    // N3 — aide « Réduire » + panneau Insérer ouvert
    hide(k[1])
    hide(k[2])

    // N5 — co-édition sur le chapeau
    // (Le HTML source place des <div>/<button> de la bulle dans un <p> : le parseur HTML ferme le <p>
    // et éclate le paragraphe ; on reconstruit donc le chapeau sans décoration, mêmes styles.)
    const leadText =
      "Cette politique définit les principes régissant l'attribution, la révision et la révocation des droits d'accès aux systèmes d'information de l'organisation. Elle s'applique à l'ensemble des collaborateurs, prestataires et comptes de service."
    const leadP = document.createElement('p')
    leadP.setAttribute(
      'style',
      'font-size: 15.5px; line-height: 1.7; color: #43434A; margin: 0; outline: none;',
    )
    leadP.textContent = leadText
    k[4].replaceChildren(leadP)

    // N6 — paragraphe « en cours d'édition »
    const editing = k[6].querySelector('p')
    editing.style.outline = 'none'
    editing.style.padding = '0'
    editing.querySelectorAll('span[style*="inline-block"]').forEach((s) => s.remove())

    // N7 — puces inline → texte brut
    const chips = [...k[7].querySelectorAll('span[contenteditable="false"]')]
    const labels = ['IAM-482 Durcir la revue N2', '15 octobre 2026']
    chips.forEach((chip, i) => chip.replaceWith(document.createTextNode(labels[i] ?? '')))

    // N8 — bloc de code sans en-tête, sans opacité de déplacement
    k[8].classList.remove('dragging')
    k[8].style.opacity = '1'
    const codeWrap = k[8].querySelector('div[contenteditable="false"]')
    codeWrap?.firstElementChild?.remove()

    // N9 — blocs hors StarterKit : indicateur de dépôt, lien réduit, encadré, tâches
    hide(k[9])
    hide(k[10])
    hide(k[11])
    hide(k[14])

    // N10 — règle de glossaire
    const panel = row.children[1]
    hide(panel.children[3])
  })
}

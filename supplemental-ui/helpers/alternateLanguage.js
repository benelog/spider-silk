'use strict'

/**
 * The URL of the current page in another language's component, for the
 * language switch and the hreflang links.
 *
 * The English manual is the component ROOT and the Korean one is ko, page for
 * page. The same page in the same version answers when it exists; a version
 * the other language never had (a release older than the translation) falls
 * back to that component's latest version, and to its start page when the page
 * is not there either. Undefined when the component does not exist or the
 * current page belongs to none, such as the 404 page.
 */
module.exports = (componentName, { data: { root } }) => {
  const { contentCatalog, page } = root
  if (!contentCatalog || !page || !page.component) return
  const component = contentCatalog.getComponent(componentName)
  if (!component) return
  const lookup = (version) =>
    contentCatalog.getById({
      component: componentName,
      version,
      module: page.module,
      family: 'page',
      relative: page.relativeSrcPath,
    })
  const same = lookup(page.version)
  if (same) return same.pub.url
  const latest = component.latest
  if (!latest) return
  const fallback = lookup(latest.version)
  return fallback ? fallback.pub.url : latest.url
}

package org.fao.geonet.standards.model.labels;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;


/**
 * Java class for anonymous complex type.
 *
 * <p>The following schema fragment specifies the expected content contained within this class.
 *
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element ref="{}element" maxOccurs="unbounded"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "element"
})
@XmlRootElement(name = "labels")
public class Labels {

  @XmlElement(required = true)
  protected List<Element> element;

  /**
   * A descriptor with label, definition, help text and suggestions Gets the value of the element
   * property.
   *
   * <p>
   * This accessor method returns a reference to the live list, not a snapshot. Therefore any
   * modification you make to the returned list will be present inside the JAXB object. This is why
   * there is not a <CODE>set</CODE> method for the element property.
   *
   * For example, to add a new item, do as follows:
   * <pre>
   *    getElement().add(newItem);
   * </pre>
   *
   *
   * Objects of the following type(s) are allowed in the list {@link Element }
   * </p>
   */
  public List<Element> getElement() {
    if (element == null) {
      element = new ArrayList<Element>();
    }
    return this.element;
  }

}

ALTER TABLE cms.admin_role ADD COLUMN version bigint NOT NULL DEFAULT 0;
CREATE TABLE cms.role_permission (
 role_id text NOT NULL REFERENCES cms.admin_role(id),
 permission_key text NOT NULL, action text NOT NULL CHECK (action IN ('READ','MANAGE')),
 PRIMARY KEY (role_id, permission_key)
);
CREATE TABLE cms.menu_tree_revision (id integer PRIMARY KEY CHECK (id=1), revision bigint NOT NULL);
INSERT INTO cms.menu_tree_revision VALUES (1,0);
CREATE TABLE cms.menu_node (
 id text PRIMARY KEY, parent_id text REFERENCES cms.menu_node(id),
 sort_order integer NOT NULL CHECK (sort_order>=0), title text NOT NULL,
 type text NOT NULL CHECK (type IN ('GROUP','PAGE')), route_key text, permission_key text,
 visible boolean NOT NULL,
 CHECK (parent_id IS DISTINCT FROM id),
 CHECK ((type='GROUP' AND route_key IS NULL AND permission_key IS NULL)
     OR (type='PAGE' AND route_key IS NOT NULL AND permission_key IS NOT NULL))
);
CREATE INDEX menu_node_parent_order ON cms.menu_node(parent_id,sort_order);
